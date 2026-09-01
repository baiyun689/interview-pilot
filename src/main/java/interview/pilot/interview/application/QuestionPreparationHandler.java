package interview.pilot.interview.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class QuestionPreparationHandler {
  public static final String SELF_INTRODUCTION =
      "请先做一个简短的自我介绍，重点说明与你应聘的 Java 后端岗位最相关的经历。";

  private final AsyncTaskRepository tasks;
  private final InterviewSessionRepository sessions;
  private final InterviewQuestionCardRepository cards;
  private final PhaseRagRetriever rag;
  private final QuestionDeckGenerator generator;
  private final FollowUpQuotaAllocator quotas;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  public QuestionPreparationHandler(
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewQuestionCardRepository cards,
      PhaseRagRetriever rag,
      QuestionDeckGenerator generator,
      FollowUpQuotaAllocator quotas,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.tasks = tasks;
    this.sessions = sessions;
    this.cards = cards;
    this.rag = rag;
    this.generator = generator;
    this.quotas = quotas;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Short inspection transaction, followed by RAG/LLM work outside a transaction. */
  public Outcome prepare(TaskMessage message) {
    Target target = inspect(message);
    if (target.terminal()) return Outcome.STALE;

    var ragByPhase = new EnumMap<InterviewPhase, RagContextSnapshot>(InterviewPhase.class);
    for (InterviewPhase phase : List.of(
        InterviewPhase.FUNDAMENTALS,
        InterviewPhase.PROJECT_EXPERIENCE,
        InterviewPhase.SCENARIO_TRADEOFF)) {
      ragByPhase.put(phase, rag.retrieve(target.brief(), phase));
    }
    PreparedQuestionDeck deck = generator.generate(target.brief(), ragByPhase);
    return transactions.execute(status -> persist(message, target, ragByPhase, deck));
  }

  public Target inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  public boolean markInvalid(TaskMessage message) {
    return terminalize(message, AsyncTaskStatus.FAILED, "INVALID_QUESTION_DECK");
  }

  public boolean markDead(TaskMessage message) {
    return terminalize(message, AsyncTaskStatus.DEAD, "QUESTION_PREPARATION_RETRY_EXHAUSTED");
  }

  private Target inspectInTransaction(TaskMessage message) {
    requireMessage(message);
    AsyncTaskEntity task = tasks.findByTaskId(message.taskId())
        .orElseThrow(() -> new IllegalStateException("Question preparation task not found"));
    UUID sessionId = sessionId(task.getBizKey());
    InterviewSessionEntity session = sessions.findBySessionId(sessionId)
        .orElseThrow(() -> new IllegalStateException("Question preparation session not found"));
    boolean stale = task.getTaskType() != AsyncTaskType.INTERVIEW_QUESTION_PREPARATION
        || message.taskType() != AsyncTaskType.INTERVIEW_QUESTION_PREPARATION
        || !task.getBizKey().equals(message.bizKey())
        || task.getExecutionEpoch() != message.executionEpoch()
        || task.getUserAccountId() == null
        || !task.getUserAccountId().equals(session.getUserAccountId());
    boolean terminal = stale
        || task.getStatus() == AsyncTaskStatus.COMPLETED
        || task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD
        || session.getStatus() != SessionStatus.PREPARING;
    if (terminal) return new Target(sessionId, session.getId(), null, true);
    if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
      task.setAttemptCount(task.getAttemptCount() + 1);
    }
    return new Target(
        sessionId, session.getId(), decode(session.getBriefSnapshot(), InterviewBriefSnapshot.class),
        false);
  }

  private Outcome persist(
      TaskMessage message,
      Target target,
      Map<InterviewPhase, RagContextSnapshot> ragByPhase,
      PreparedQuestionDeck deck) {
    AsyncTaskEntity task = tasks.findByTaskId(message.taskId()).orElseThrow();
    InterviewSessionEntity session = sessions.findBySessionId(target.sessionId()).orElseThrow();
    if (task.getExecutionEpoch() != message.executionEpoch()
        || task.getStatus() != AsyncTaskStatus.PUBLISHED
        || session.getStatus() != SessionStatus.PREPARING) {
      return Outcome.STALE;
    }
    if (cards.countBySessionId(session.getId()) != 0) {
      throw new IllegalStateException("Partial or duplicate question deck detected");
    }

    RagContextSnapshot selfRag = new RagContextSnapshot(
        RagStatus.NOT_REQUESTED, "", "", List.of(), null);
    cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍",
        SELF_INTRODUCTION, "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED,
        encode(selfRag), "[]", 0, null));
    for (PreparedQuestionDeck.PreparedQuestion question : deck.questions()) {
      RagContextSnapshot phaseRag = ragByPhase.get(question.phase());
      cards.save(InterviewQuestionCardEntity.create(
          session.getId(), question.phase(), question.sequence(), question.topic(),
          question.question(), encode(question.focusPoints()), question.groundingMode(),
          phaseRag.status(), encode(phaseRag), encode(question.evidenceRefs()),
          quotas.allocate(question.phase()), question.fallbackFollowUp()));
    }
    cards.flush();
    session.preparationReady();
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    return Outcome.COMPLETED;
  }

  private boolean terminalize(
      TaskMessage message, AsyncTaskStatus taskStatus, String safeError) {
    Boolean result = transactions.execute(status -> {
      requireMessage(message);
      var task = tasks.findByTaskId(message.taskId()).orElse(null);
      if (task == null || task.getExecutionEpoch() != message.executionEpoch()
          || task.getStatus() == AsyncTaskStatus.COMPLETED) return false;
      var session = sessions.findBySessionId(sessionId(task.getBizKey())).orElse(null);
      if (session == null || session.getStatus() != SessionStatus.PREPARING) return false;
      cards.deleteAllBySessionId(session.getId());
      task.setStatus(taskStatus);
      task.setLastError(safeError);
      session.preparationFailed(safeError);
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private void requireMessage(TaskMessage message) {
    if (message == null || message.taskId() == null) {
      throw new IllegalArgumentException("Question preparation message is required");
    }
  }

  private UUID sessionId(String bizKey) {
    if (bizKey == null || !bizKey.startsWith("interview:")) {
      throw new IllegalStateException("Invalid question preparation business key");
    }
    return UUID.fromString(bizKey.substring("interview:".length()));
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Interview snapshot is invalid", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Question snapshot could not be encoded", exception);
    }
  }

  public enum Outcome { COMPLETED, STALE }

  public record Target(
      UUID sessionId, Long sessionDatabaseId, InterviewBriefSnapshot brief, boolean terminal) { }
}
