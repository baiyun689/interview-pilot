package interview.pilot.interview.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.policy.AnswerEvaluationRetryPolicy;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.EvalStatus;
import interview.pilot.interview.domain.RubricPoint;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the ANSWER_EVALUATION consumer (see {@code AnswerEvaluationListener}). The slow model
 * call is sandwiched between two short transactions, mirroring the answer/transcription handlers:
 * <ol>
 *   <li>{@code begin} validates identity/ownership/epoch, loads the completed turn and its frozen
 *       card (rubric + question-scoped snapshot), marks the task PUBLISHED and assembles the
 *       evaluator input — a turn already judged terminates idempotently;</li>
 *   <li>the {@link AnswerEvaluator} runs outside any transaction;</li>
 *   <li>{@code complete} re-reads the row and, behind the optimistic {@code @Version}, verifies the
 *       turn is still COMPLETED, still PENDING evaluation and still carries the same requestId, so
 *       a stale or duplicated result can never overwrite a newer one.</li>
 * </ol>
 * Retry exhaustion ({@link #markDead}) only flips eval_status to FAILED; the answer and the
 * interview flow are never affected.
 */
@Component
public class AnswerEvaluationHandler {
  private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationHandler.class);

  private final AsyncTaskRepository tasks;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final InterviewQuestionCardRepository cards;
  private final AnswerEvaluator evaluator;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  public AnswerEvaluationHandler(
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewQuestionCardRepository cards,
      AnswerEvaluator evaluator,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.tasks = tasks;
    this.sessions = sessions;
    this.turns = turns;
    this.cards = cards;
    this.evaluator = evaluator;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public Target inspect(TaskMessage message) {
    return transactions.execute(status -> {
      AsyncTaskEntity task = requireTask(message);
      Key key = parseKey(task.getBizKey());
      boolean terminal = message.executionEpoch() != task.getExecutionEpoch()
          || task.getStatus() == AsyncTaskStatus.COMPLETED
          || task.getStatus() == AsyncTaskStatus.FAILED
          || task.getStatus() == AsyncTaskStatus.DEAD;
      return new Target(key.sessionId(), terminal, task.getAttemptCount(), task.getExecutionEpoch());
    });
  }

  public Outcome evaluate(TaskMessage message) {
    Begin begin = transactions.execute(status -> begin(message));
    if (begin.stale()) {
      return Outcome.STALE;
    }
    Work work = begin.work();
    AnswerEvaluation evaluation = evaluator.evaluate(work.input());
    return transactions.execute(status -> complete(work, evaluation));
  }

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireTask(message);
      Key key = parseKey(task.getBizKey());
      InterviewSessionEntity session = session(task, key);
      InterviewTurnEntity turn = turn(session, key.turnNo());
      if (task.getExecutionEpoch() != message.executionEpoch()
          || task.getAttemptCount() != attemptGeneration) {
        return false;
      }
      if (task.getStatus() == AsyncTaskStatus.DEAD && turn.getEvalStatus() == EvalStatus.FAILED) {
        return true;
      }
      if (task.getStatus() != AsyncTaskStatus.PENDING
          && task.getStatus() != AsyncTaskStatus.PUBLISHED) {
        return false;
      }
      if (turn.getEvalStatus() == EvalStatus.PENDING) {
        turn.failEvaluation();
      }
      task.setStatus(AsyncTaskStatus.DEAD);
      task.setLastError("ANSWER_EVALUATION_RETRY_EXHAUSTED");
      log.warn("answer_evaluation_exhausted taskId={} sessionId={} turnNo={}",
          task.getTaskId(), key.sessionId(), key.turnNo());
      return true;
    }));
  }

  private Begin begin(TaskMessage message) {
    AsyncTaskEntity task = requireTask(message);
    Key key = parseKey(task.getBizKey());
    if (task.getExecutionEpoch() != message.executionEpoch()) {
      return Begin.beginStale();
    }
    InterviewSessionEntity session = session(task, key);
    InterviewTurnEntity turn = turn(session, key.turnNo());

    // Idempotent replay: a turn already at a terminal eval state must not be judged twice. Close
    // the task row so a redelivered message cannot stay PUBLISHED forever.
    if (turn.getEvalStatus().terminal()) {
      task.setStatus(AsyncTaskStatus.COMPLETED);
      task.setLastError(null);
      return Begin.beginStale();
    }
    if (turn.getEvalStatus() != EvalStatus.PENDING) {
      throw new IllegalStateException("answer evaluation turn state is inconsistent");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("answer evaluation task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastError(null);

    InterviewQuestionCardEntity card = cards.findById(turn.getSourceCardId())
        .orElseThrow(() -> new IllegalStateException("source question card is missing"));
    RagContextSnapshot snapshot = decode(card.getRagContextSnapshot(), RagContextSnapshot.class);
    AnswerEvaluationInput input = new AnswerEvaluationInput(
        turn.getPhase(),
        turn.getQuestionText(),
        turn.getAnswerText(),
        session.getDifficulty(),
        rubricOf(card),
        card.getGroundingMode(),
        snapshot,
        session.getProviderId(),
        session.getModelName());
    Work work = new Work(
        task.getTaskId(), session.getId(), turn.getId(), key.turnNo(), turn.getRequestId(),
        task.getAttemptCount(), task.getExecutionEpoch(), input);
    return Begin.of(work);
  }

  private Outcome complete(Work work, AnswerEvaluation evaluation) {
    AsyncTaskEntity task = tasks.findByTaskId(work.taskId())
        .orElseThrow(() -> new IllegalStateException("answer evaluation task is missing"));
    InterviewTurnEntity turn = turns.findById(work.turnId())
        .orElseThrow(() -> new IllegalStateException("answer evaluation turn is missing"));
    if (task.getExecutionEpoch() != work.executionEpoch()
        || task.getAttemptCount() != work.attemptGeneration()
        || task.getStatus() != AsyncTaskStatus.PUBLISHED
        || turn.getEvalStatus() != EvalStatus.PENDING
        || !Objects.equals(turn.getRequestId(), work.requestId())) {
      // A stale/duplicated result (or a newer answer) must never overwrite the current row.
      return Outcome.STALE;
    }
    turn.attachEvaluation(encode(evaluation), evaluation.status());
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    return Outcome.TERMINAL;
  }

  /** Frozen rubric when present; otherwise derive generic points from the card's focus points. */
  private List<RubricPoint> rubricOf(InterviewQuestionCardEntity card) {
    if (card.getRubric() != null && !card.getRubric().isBlank()) {
      return decodeRubric(card.getRubric());
    }
    List<String> focusPoints = decodeStringList(card.getFocusPoints());
    List<RubricPoint> fallback = focusPoints.stream()
        .map(point -> {
          String key = point.trim();
          if (key.length() > 40) {
            key = key.substring(0, 40);
          }
          return new RubricPoint(key, "候选人应能准确、完整地阐述该要点。");
        })
        .toList();
    if (!fallback.isEmpty()) {
      return fallback;
    }
    return List.of(new RubricPoint("回答的完整性与准确性", "候选人应能围绕问题给出准确、完整且有深度的阐述。"));
  }

  private AsyncTaskEntity requireTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.ANSWER_EVALUATION || message.bizKey() == null) {
      throw new IllegalArgumentException("answer evaluation message identity is invalid");
    }
    AsyncTaskEntity task = tasks.findByTaskId(message.taskId())
        .orElseThrow(() -> new IllegalArgumentException("answer evaluation task not found"));
    if (task.getTaskType() != AsyncTaskType.ANSWER_EVALUATION
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("answer evaluation message does not match stored task");
    }
    return task;
  }

  private InterviewSessionEntity session(AsyncTaskEntity task, Key key) {
    return sessions.findBySessionIdAndUserAccountId(key.sessionId(), task.getUserAccountId())
        .orElseThrow(() -> new IllegalArgumentException("answer evaluation session not found"));
  }

  private InterviewTurnEntity turn(InterviewSessionEntity session, int turnNo) {
    return turns.findBySessionIdAndTurnNo(session.getId(), turnNo)
        .orElseThrow(() -> new IllegalArgumentException("answer evaluation turn not found"));
  }

  private Key parseKey(String bizKey) {
    try {
      String body = bizKey.substring(AnswerEvaluationRetryPolicy.BIZ_KEY_PREFIX.length());
      int separator = body.lastIndexOf(':');
      UUID sessionId = UUID.fromString(body.substring(0, separator));
      int turnNo = Integer.parseInt(body.substring(separator + 1));
      if (turnNo < 1) {
        throw new IllegalArgumentException();
      }
      return new Key(sessionId, turnNo);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("answer evaluation business key is invalid");
    }
  }

  private List<RubricPoint> decodeRubric(String json) {
    try {
      return objectMapper.readValue(json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, RubricPoint.class));
    } catch (JacksonException exception) {
      throw new IllegalStateException("stored question rubric is invalid", exception);
    }
  }

  private List<String> decodeStringList(String json) {
    try {
      return objectMapper.readValue(json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JacksonException exception) {
      throw new IllegalStateException("stored focus points are invalid", exception);
    }
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("stored RAG snapshot is invalid", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("answer evaluation could not be encoded", exception);
    }
  }

  public enum Outcome { TERMINAL, STALE }

  public record Target(UUID sessionId, boolean terminal, int attemptGeneration, int executionEpoch) { }

  private record Key(UUID sessionId, int turnNo) { }

  private record Work(
      UUID taskId, Long sessionId, Long turnId, int turnNo, UUID requestId,
      int attemptGeneration, int executionEpoch, AnswerEvaluationInput input) { }

  private record Begin(Work work, boolean stale) {
    static Begin of(Work work) {
      return new Begin(work, false);
    }
    static Begin beginStale() {
      return new Begin(null, true);
    }
  }
}
