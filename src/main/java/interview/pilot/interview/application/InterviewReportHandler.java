package interview.pilot.interview.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewReportEntity;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.common.observability.AiMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InterviewReportHandler {
  static final String INVALID_REPORT_ERROR = "Interview report response was invalid";
  static final String RETRYABLE_ERROR = "Interview report generation temporarily unavailable";
  static final String DEAD_ERROR = "Interview report generation retries exhausted";

  private final ReportGenerator generator;
  private final AsyncTaskRepository tasks;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final InterviewReportRepository reports;
  private final JobProfileRepository jobs;
  private final ObjectMapper objectMapper;
  private final StoredAnswerResultCodec answerCodec;
  private final StoredInterviewReportCodec reportCodec;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;

  public InterviewReportHandler(
      ReportGenerator generator,
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewReportRepository reports,
      JobProfileRepository jobs,
      ObjectMapper objectMapper,
      StoredAnswerResultCodec answerCodec,
      StoredInterviewReportCodec reportCodec,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.generator = generator;
    this.tasks = tasks;
    this.sessions = sessions;
    this.turns = turns;
    this.reports = reports;
    this.jobs = jobs;
    this.objectMapper = objectMapper;
    this.answerCodec = answerCodec;
    this.reportCodec = reportCodec;
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
  }

  /** Opens a short transaction, calls AI without one, then fences the final short transaction. */
  public Outcome handle(UUID taskId) {
    Work work = transactions.execute(status -> begin(taskId));
    return generate(work);
  }

  public Outcome handle(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> beginMessage(message));
    return begin.stale() ? Outcome.STALE : generate(begin.work());
  }

  private Outcome generate(Work work) {
    if (work == null) return Outcome.TERMINAL;
    try {
      InterviewReport report = generator.generate(
          work.providerId(), work.modelName(), work.evidence(), work.skill());
      if (report == null) throw new AiStructuredOutputException("Empty report");
      String snapshot = reportCodec.write(work.publicSessionId(), report);
      return transactions.execute(status -> complete(work, report, snapshot));
    } catch (AiStructuredOutputException | IllegalArgumentException exception) {
      return transactions.execute(status -> failInvalid(work));
    } catch (RuntimeException exception) {
      boolean current = Boolean.TRUE.equals(
          transactions.execute(status -> recordRetryableFailure(work)));
      if (!current) return Outcome.STALE;
      throw new ReportGenerationRetryableException(work.attemptGeneration());
    }
  }

  public Target inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      InterviewSessionEntity session = requireSession(task);
      return markDead(task, session, attemptGeneration, message.executionEpoch());
    }));
  }

  public boolean markDeadCurrent(TaskMessage message) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      InterviewSessionEntity session = requireSession(task);
      return markDead(task, session, task.getAttemptCount(), message.executionEpoch());
    }));
  }

  private Target inspectInTransaction(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.INTERVIEW_EVALUATION
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Interview report message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Interview report message does not match stored task");
    }
    InterviewSessionEntity session = requireSession(task);
    if (message.executionEpoch() < task.getExecutionEpoch()) {
      return new Target(
          session.getSessionId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    if (message.executionEpoch() != task.getExecutionEpoch()) {
      throw new IllegalArgumentException("Interview report message epoch is invalid");
    }
    boolean succeeded = task.getStatus() == AsyncTaskStatus.COMPLETED
        && session.getStatus() == SessionStatus.COMPLETED
        && reports.findBySessionId(session.getId()).isPresent();
    boolean stopped = task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    return new Target(
        session.getSessionId(), succeeded || stopped,
        task.getAttemptCount(), task.getExecutionEpoch());
  }

  private Work begin(UUID taskId) {
    AsyncTaskEntity task = requireTask(taskId);
    return begin(task);
  }

  private BeginResult beginMessage(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    if (task.getExecutionEpoch() != message.executionEpoch()) {
      return new BeginResult(null, true);
    }
    return new BeginResult(begin(task), false);
  }

  private Work begin(AsyncTaskEntity task) {
    InterviewSessionEntity session = requireSession(task);
    if (task.getStatus() == AsyncTaskStatus.COMPLETED
        && session.getStatus() == SessionStatus.COMPLETED
        && reports.findBySessionId(session.getId()).isPresent()) {
      return null;
    }
    if (task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD) return null;
    if (session.getStatus() != SessionStatus.EVALUATING
        || reports.findBySessionId(session.getId()).isPresent()) {
      throw new IllegalStateException("Interview report state is inconsistent");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Interview report task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastError(null);
    InterviewPlan plan = readPlan(session.getPlanSnapshot());
    List<ReportEvidence> evidence = validatedEvidence(session, plan);
    var job = jobs.findById(session.getJobProfileId())
        .orElseThrow(() -> new IllegalStateException("Interview job profile is missing"));
    SkillSnapshot skill = readSkill(job.getSkillSnapshot());
    return new Work(task.getTaskId(), session.getId(), session.getUserAccountId(), session.getSessionId(),
        session.getProviderId(), session.getModelName(), evidence, skill, task.getAttemptCount());
  }

  private SkillSnapshot readSkill(String snapshot) {
    try {
      SkillSnapshot skill = objectMapper.readValue(snapshot, SkillSnapshot.class);
      if (skill == null) throw new IllegalStateException("Stored interview skill is invalid");
      return skill;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored interview skill is invalid", exception);
    }
  }

  private InterviewPlan readPlan(String snapshot) {
    try {
      InterviewPlan plan = objectMapper.readValue(snapshot, InterviewPlan.class);
      if (plan == null) throw new IllegalStateException("Stored interview plan is invalid");
      return plan;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored interview plan is invalid", exception);
    }
  }

  private List<ReportEvidence> validatedEvidence(
      InterviewSessionEntity session, InterviewPlan plan) {
    var storedTurns = turns.findAllBySessionIdOrderByTurnNo(session.getId());
    if (storedTurns.isEmpty() || storedTurns.size() != session.getCurrentTurnNo()) {
      throw new IllegalStateException("Completed interview evidence is incomplete");
    }
    var result = new ArrayList<ReportEvidence>();
    for (var turn : storedTurns) {
      if (turn.getStatus() != TurnStatus.COMPLETED || turn.getRequestId() == null
          || turn.getScore() == null) {
        throw new IllegalStateException("Completed interview evidence is invalid");
      }
      AnswerProcessingResult answer = answerCodec.readCompleted(
          turn.getEvaluationSnapshot(), session.getSessionId(), turn.getRequestId(), turn.getTurnNo());
      if (turn.getScore().compareTo(BigDecimal.valueOf(answer.evaluation().score())) != 0
          || !Objects.equals(turn.getFeedbackText(), answer.evaluation().feedback())) {
        throw new IllegalStateException("Completed interview evidence does not match stored result");
      }
      var directive = answer.currentDirective();
      var item = plan.itemFor(turn.getTargetCompetency());
      RagContextSnapshot rag = readRag(turn.getRagContextSnapshot());
      java.util.Set<String> cited = new java.util.LinkedHashSet<>(rag.evidenceRefs());
      List<ReportEvidence.SourceReference> sources = rag.chunks().stream()
          .filter(chunk -> cited.contains(chunk.pointId()))
          .map(chunk -> new ReportEvidence.SourceReference(
              chunk.pointId(), chunk.filename(), chunk.documentRevision(),
              chunk.section(), chunk.pageNumber(), chunk.score()))
          .toList();
      result.add(new ReportEvidence(
          turn.getTurnNo(), turn.getTargetCompetency(), answer.evaluation().score(),
          answer.evaluation().feedback(), answer.evaluation().evidence(),
          directive == null ? item.stageId() : directive.stageId(),
          directive == null ? item.questionModes().getFirst() : directive.questionMode(),
          directive == null ? item.evidenceTargets() : directive.evidenceTargets(),
          item.rationale(), rag.groundingMode(), rag.evidenceRefs(), sources,
          answer.evaluation().referenceFacts(), answer.evaluation().conflictFacts()));
    }
    return List.copyOf(result);
  }

  private RagContextSnapshot readRag(String snapshot) {
    if (snapshot == null || snapshot.isBlank()) return RagContextSnapshot.notConfigured();
    try {
      RagContextSnapshot rag = objectMapper.readValue(snapshot, RagContextSnapshot.class);
      return rag == null ? RagContextSnapshot.notConfigured() : rag;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored grounding snapshot is invalid", exception);
    }
  }

  private Outcome complete(Work work, InterviewReport report, String snapshot) {
    AsyncTaskEntity task = requireTask(work.taskId());
    InterviewSessionEntity session = sessions.findByIdAndUserAccountId(
        work.sessionDatabaseId(), work.userAccountId()).orElse(null);
    if (!current(task, session, work)) return Outcome.STALE;
    if (reports.findBySessionId(session.getId()).isPresent()) return Outcome.STALE;
    reports.saveAndFlush(InterviewReportEntity.create(
        session.getId(), report.overallScore(), report.summary(), snapshot));
    session.completeEvaluation();
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.INTERVIEW_EVALUATION));
    sessions.saveAndFlush(session);
    return Outcome.TERMINAL;
  }

  private Outcome failInvalid(Work work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    InterviewSessionEntity session = sessions.findByIdAndUserAccountId(
        work.sessionDatabaseId(), work.userAccountId()).orElse(null);
    if (!current(task, session, work)) return Outcome.STALE;
    task.setStatus(AsyncTaskStatus.FAILED);
    task.setLastError(INVALID_REPORT_ERROR);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.INTERVIEW_EVALUATION, "failed"));
    return Outcome.TERMINAL;
  }

  private boolean recordRetryableFailure(Work work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    InterviewSessionEntity session = sessions.findByIdAndUserAccountId(
        work.sessionDatabaseId(), work.userAccountId()).orElse(null);
    if (!current(task, session, work)) return false;
    task.setLastError(RETRYABLE_ERROR);
    return true;
  }

  private boolean current(AsyncTaskEntity task, InterviewSessionEntity session, Work work) {
    return session != null
        && task.getUserAccountId() != null
        && task.getUserAccountId().equals(work.userAccountId())
        && session.getId().equals(work.sessionDatabaseId())
        && session.getUserAccountId().equals(work.userAccountId())
        && task.getStatus() == AsyncTaskStatus.PUBLISHED
        && task.getAttemptCount() == work.attemptGeneration()
        && session.getStatus() == SessionStatus.EVALUATING;
  }

  private AsyncTaskEntity requireTask(UUID taskId) {
    return tasks.findByTaskId(Objects.requireNonNull(taskId, "taskId"))
        .orElseThrow(() -> new IllegalArgumentException("Interview report task not found"));
  }

  private AsyncTaskEntity requireMatchingTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.INTERVIEW_EVALUATION
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Interview report message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Interview report message does not match stored task");
    }
    return task;
  }

  private boolean markDead(
      AsyncTaskEntity task,
      InterviewSessionEntity session,
      int expectedAttemptGeneration,
      int expectedExecutionEpoch) {
    if (task.getExecutionEpoch() != expectedExecutionEpoch
        || task.getAttemptCount() != expectedAttemptGeneration
        || session.getStatus() != SessionStatus.EVALUATING) {
      return false;
    }
    if (task.getStatus() == AsyncTaskStatus.DEAD) return true;
    if (task.getStatus() != AsyncTaskStatus.PENDING
        && task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      return false;
    }
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setLastError(DEAD_ERROR);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.INTERVIEW_EVALUATION, "dead"));
    return true;
  }

  private InterviewSessionEntity requireSession(AsyncTaskEntity task) {
    if (task.getTaskType() != AsyncTaskType.INTERVIEW_EVALUATION) {
      throw new IllegalArgumentException("Task is not an interview report task");
    }
    String prefix = "interview:";
    if (task.getBizKey() == null || !task.getBizKey().startsWith(prefix)) {
      throw new IllegalArgumentException("Interview report business key is invalid");
    }
    try {
      UUID publicId = UUID.fromString(task.getBizKey().substring(prefix.length()));
      Long ownerId = Objects.requireNonNull(
          task.getUserAccountId(), "Interview report task owner is required");
      return sessions.findBySessionIdAndUserAccountId(publicId, ownerId)
          .orElseThrow(() -> new IllegalArgumentException("Interview session not found"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Interview report business key is invalid");
    }
  }

  private record Work(
      UUID taskId, Long sessionDatabaseId, Long userAccountId, UUID publicSessionId,
      String providerId, String modelName, List<ReportEvidence> evidence,
      SkillSnapshot skill, int attemptGeneration) {}

  private record BeginResult(Work work, boolean stale) {}

  public enum Outcome { TERMINAL, STALE }

  public record Target(
      UUID sessionId, boolean terminal, int attemptGeneration, int executionEpoch) {
    public Target(UUID sessionId, boolean terminal) {
      this(sessionId, terminal, 0, 0);
    }
  }
}
