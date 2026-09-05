package interview.pilot.interview.application;

import java.util.LinkedHashMap;
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
  private final QuestionSkeletonGenerator skeletonGenerator;
  private final QuestionRagRetriever questionRagRetriever;
  private final RubricGenerator rubricGenerator;
  private final FollowUpQuotaAllocator quotas;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  public QuestionPreparationHandler(
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewQuestionCardRepository cards,
      QuestionSkeletonGenerator skeletonGenerator,
      QuestionRagRetriever questionRagRetriever,
      RubricGenerator rubricGenerator,
      FollowUpQuotaAllocator quotas,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.tasks = tasks;
    this.sessions = sessions;
    this.cards = cards;
    this.skeletonGenerator = skeletonGenerator;
    this.questionRagRetriever = questionRagRetriever;
    this.rubricGenerator = rubricGenerator;
    this.quotas = quotas;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Short inspection transaction, then the two-stage generation pipeline outside a transaction:
   * question skeletons -> one precise RAG snapshot per question -> per-question rubric deck.
   */
  public Outcome prepare(TaskMessage message) {
    Target target = inspect(message);
    if (target.terminal()) return Outcome.STALE;
    InterviewBriefSnapshot brief = target.brief();

    // Stage 1: question skeletons (text + knowledge point + focus points), no RAG involved.
    List<QuestionSkeletonOutput.Skeleton> skeletons = skeletonGenerator.generate(brief);

    // Stage 2: question-scoped retrieval (one precise snapshot per question).
    Map<QuestionCardKey, RagContextSnapshot> questionSnapshots = new LinkedHashMap<>();
    for (QuestionSkeletonOutput.Skeleton skeleton : skeletons) {
      QuestionRetrievalSeed seed = new QuestionRetrievalSeed(
          skeleton.phase(),
          skeleton.knowledgePoint(),
          skeleton.retrievalKeywords(),
          skeleton.question(),
          brief.difficulty());
      questionSnapshots.put(
          QuestionCardKey.of(skeleton.phase(), skeleton.sequence()),
          questionRagRetriever.retrieve(brief.knowledgeScope(), seed));
    }

    // Stage 3: per-question rubric + deterministic grounding decision, frozen into a deck.
    PreparedQuestionDeck deck = rubricGenerator.generate(brief, skeletons, questionSnapshots);

    return transactions.execute(status -> persist(message, target, questionSnapshots, deck));
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
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
      task.setAttemptCount(task.getAttemptCount() + 1);
    } else if (task.getStatus() == AsyncTaskStatus.PUBLISHED && task.getAttemptCount() == 0) {
      // The outbox dispatcher atomically claimed PENDING -> PUBLISHED before broker publish.
      // This is still the first preparation execution, not a duplicate delivery.
      task.setAttemptCount(1);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Question preparation task state is inconsistent");
    }
    return new Target(
        sessionId, session.getId(), decode(session.getBriefSnapshot(), InterviewBriefSnapshot.class),
        false);
  }

  private Outcome persist(
      TaskMessage message,
      Target target,
      Map<QuestionCardKey, RagContextSnapshot> questionSnapshots,
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
      QuestionCardKey key = QuestionCardKey.of(question.phase(), question.sequence());
      RagContextSnapshot questionRag = questionSnapshots.get(key);
      if (questionRag == null) {
        throw new InvalidQuestionDeckException("missing question RAG snapshot for " + key);
      }
      cards.save(InterviewQuestionCardEntity.create(
          session.getId(), question.phase(), question.sequence(), question.topic(),
          question.question(), encode(question.focusPoints()),
          question.knowledgePoint(), encode(question.retrievalKeywords()),
          question.groundingMode(), questionRag.status(), encode(questionRag),
          encode(question.evidenceRefs()), encode(question.rubric()),
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
