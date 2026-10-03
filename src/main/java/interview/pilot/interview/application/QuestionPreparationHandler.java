package interview.pilot.interview.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
  private final long leaseSeconds;

  public QuestionPreparationHandler(
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewQuestionCardRepository cards,
      QuestionSkeletonGenerator skeletonGenerator,
      QuestionRagRetriever questionRagRetriever,
      RubricGenerator rubricGenerator,
      FollowUpQuotaAllocator quotas,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      @Value("${app.async.question-preparation.lease-duration:11m}") Duration leaseDuration) {
    this.tasks = tasks;
    this.sessions = sessions;
    this.cards = cards;
    this.skeletonGenerator = skeletonGenerator;
    this.questionRagRetriever = questionRagRetriever;
    this.rubricGenerator = rubricGenerator;
    this.quotas = quotas;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    if (leaseDuration == null || leaseDuration.getSeconds() < 1
        || leaseDuration.compareTo(Duration.ofHours(1)) > 0 || leaseDuration.getNano() != 0) {
      throw new IllegalArgumentException("Question preparation lease must be whole seconds between 1s and 1h");
    }
    this.leaseSeconds = leaseDuration.getSeconds();
  }

  /**
   * Short execution-claim transaction, then the generation pipeline outside a transaction:
   * question skeletons -> one precise RAG snapshot per question -> per-question rubric deck.
   */
  public Outcome prepare(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> begin(message));
    if (begin.skipped() != null) return begin.skipped();
    Target target = begin.target();
    try {
      return generateAndPersist(message, target);
    } catch (RuntimeException exception) {
      boolean current = Boolean.TRUE.equals(transactions.execute(status -> {
        var task = tasks.findByTaskIdForUpdate(message.taskId()).orElse(null);
        return matchesExecution(task, message, target.attemptGeneration(), target.executionToken());
      }));
      if (!current) return Outcome.STALE;
      throw new QuestionPreparationExecutionException(target.attemptGeneration(), target.executionToken(), exception);
    }
  }

  private Outcome generateAndPersist(TaskMessage message, Target target) {
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

  public boolean markInvalid(TaskMessage message, int attemptGeneration, String executionToken) {
    return terminalize(message, attemptGeneration, executionToken, AsyncTaskStatus.FAILED, "INVALID_QUESTION_DECK");
  }

  public boolean markDead(TaskMessage message, int attemptGeneration, String executionToken) {
    return terminalize(message, attemptGeneration, executionToken, AsyncTaskStatus.DEAD, "QUESTION_PREPARATION_RETRY_EXHAUSTED");
  }

  public void releaseForRetry(TaskMessage message, int attemptGeneration, String executionToken) {
    transactions.executeWithoutResult(status -> tasks.releaseExecution(message.taskId().toString(),
        AsyncTaskType.INTERVIEW_QUESTION_PREPARATION.name(), message.bizKey(), message.executionEpoch(),
        attemptGeneration, executionToken));
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
    if (terminal) return new Target(sessionId, session.getId(), null, true, task.getAttemptCount());
    if (task.getStatus() != AsyncTaskStatus.PENDING && task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Question preparation task state is inconsistent");
    }
    return new Target(
        sessionId, session.getId(), decode(session.getBriefSnapshot(), InterviewBriefSnapshot.class),
        false, task.getAttemptCount());
  }

  private BeginResult begin(TaskMessage message) {
    Target target = inspectInTransaction(message);
    if (target.terminal()) return new BeginResult(null, Outcome.STALE);
    String token = UUID.randomUUID().toString();
    if (tasks.claimExecution(message.taskId().toString(), AsyncTaskType.INTERVIEW_QUESTION_PREPARATION.name(),
        message.bizKey(), message.executionEpoch(), token, leaseSeconds) != 1) {
      return new BeginResult(null, Outcome.BUSY);
    }
    // CAS clears the persistence context; reload the generation committed by this claim.
    AsyncTaskEntity task = tasks.findByTaskId(message.taskId()).orElseThrow();
    return new BeginResult(new Target(target.sessionId(), target.sessionDatabaseId(), target.brief(), false,
        task.getAttemptCount(), token), null);
  }

  private Outcome persist(
      TaskMessage message,
      Target target,
      Map<QuestionCardKey, RagContextSnapshot> questionSnapshots,
      PreparedQuestionDeck deck) {
    AsyncTaskEntity task = tasks.findByTaskIdForUpdate(message.taskId()).orElseThrow();
    InterviewSessionEntity session = sessions.findBySessionId(target.sessionId()).orElseThrow();
    if (!matchesExecution(task, message, target.attemptGeneration(), target.executionToken())
        || !task.getUserAccountId().equals(session.getUserAccountId())
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
    task.clearExecutionLease();
    return Outcome.COMPLETED;
  }

  private boolean terminalize(
      TaskMessage message, int attemptGeneration, String executionToken, AsyncTaskStatus taskStatus, String safeError) {
    Boolean result = transactions.execute(status -> {
      requireMessage(message);
      var task = tasks.findByTaskIdForUpdate(message.taskId()).orElse(null);
      if (!matchesExecution(task, message, attemptGeneration, executionToken)) return false;
      var session = sessions.findBySessionId(sessionId(task.getBizKey())).orElse(null);
      if (session == null || session.getStatus() != SessionStatus.PREPARING
          || !task.getUserAccountId().equals(session.getUserAccountId())) return false;
      cards.deleteAllBySessionId(session.getId());
      task.setStatus(taskStatus);
      task.setLastError(safeError);
      task.clearExecutionLease();
      session.preparationFailed(safeError);
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private void requireMessage(TaskMessage message) {
    if (message == null || message.taskId() == null || message.bizKey() == null
        || message.taskType() != AsyncTaskType.INTERVIEW_QUESTION_PREPARATION) {
      throw new IllegalArgumentException("Question preparation message is required");
    }
  }

  private UUID sessionId(String bizKey) {
    if (bizKey == null || !bizKey.startsWith("interview:")) {
      throw new IllegalStateException("Invalid question preparation business key");
    }
    return UUID.fromString(bizKey.substring("interview:".length()));
  }

  private boolean matchesExecution(AsyncTaskEntity task, TaskMessage message, int generation, String token) {
    return task != null && task.getTaskType() == message.taskType()
        && task.getBizKey().equals(message.bizKey())
        && task.ownsExecution(message.executionEpoch(), generation, token);
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

  public enum Outcome { COMPLETED, STALE, BUSY }

  private record BeginResult(Target target, Outcome skipped) { }

  public record Target(
      UUID sessionId, Long sessionDatabaseId, InterviewBriefSnapshot brief, boolean terminal,
      int attemptGeneration, String executionToken) {
    public Target(UUID sessionId, Long sessionDatabaseId, InterviewBriefSnapshot brief, boolean terminal,
        int attemptGeneration) {
      this(sessionId, sessionDatabaseId, brief, terminal, attemptGeneration, null);
    }
    public Target(UUID sessionId, Long sessionDatabaseId, InterviewBriefSnapshot brief, boolean terminal) {
      this(sessionId, sessionDatabaseId, brief, terminal, 0);
    }
  }
}
