package interview.pilot.resume.application;

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
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import interview.pilot.common.observability.AiMetrics;

@Service
public class ResumeAnalysisHandler {
  static final String INVALID_PROFILE_ERROR = "Resume profile response was invalid";
  static final String RETRYABLE_ERROR = "Resume analysis temporarily unavailable";

  private final ResumeProfiler profiler;
  private final ResumeRepository resumeRepository;
  private final AsyncTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;

  public ResumeAnalysisHandler(
      ResumeProfiler profiler,
      ResumeRepository resumeRepository,
      AsyncTaskRepository taskRepository,
      ObjectMapper objectMapper,
      Validator validator,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.profiler = profiler;
    this.resumeRepository = resumeRepository;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
  }

  public Outcome handle(UUID taskId) {
    AnalysisWork work = transactions.execute(status -> begin(taskId));
    return profile(work);
  }

  public Outcome handle(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> beginMessage(message));
    return begin.stale() ? Outcome.STALE : profile(begin.work());
  }

  private Outcome profile(AnalysisWork work) {
    if (work == null) {
      return Outcome.TERMINAL;
    }

    try {
      ResumeProfile profile = profiler.profile(work.resumeText());
      validate(profile);
      String profileJson = objectMapper.writeValueAsString(profile);
      return transactions.execute(status -> complete(work, profileJson));
    } catch (AiStructuredOutputException | ConstraintViolationException | JacksonException exception) {
      return transactions.execute(status -> fail(work));
    } catch (RuntimeException exception) {
      boolean currentAttempt = Boolean.TRUE.equals(
          transactions.execute(status -> recordRetryableFailure(work)));
      if (!currentAttempt) {
        return Outcome.STALE;
      }
      throw new ResumeAnalysisRetryableException(work.attemptGeneration());
    }
  }

  public ResumeAnalysisTarget inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  private ResumeAnalysisTarget inspectInTransaction(TaskMessage message) {
    if (message == null
        || message.taskId() == null
        || message.taskType() != AsyncTaskType.RESUME_ANALYSIS
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Resume analysis message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Resume analysis message does not match stored task");
    }
    ResumeEntity resume = requireResume(task);
    if (message.executionEpoch() < task.getExecutionEpoch()) {
      return new ResumeAnalysisTarget(
          resume.getId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    if (message.executionEpoch() != task.getExecutionEpoch()) {
      throw new IllegalArgumentException("Resume analysis message epoch is invalid");
    }
    boolean succeeded = task.getStatus() == AsyncTaskStatus.COMPLETED
        && resume.getStatus() == ResumeStatus.READY;
    boolean failed = (task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD)
        && resume.getStatus() == ResumeStatus.FAILED;
    return new ResumeAnalysisTarget(
        resume.getId(), succeeded || failed,
        task.getAttemptCount(), task.getExecutionEpoch());
  }

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      ResumeEntity resume = requireResume(task);
      return markDead(task, resume, attemptGeneration, message.executionEpoch());
    }));
  }

  public boolean markDeadCurrent(TaskMessage message) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      ResumeEntity resume = requireResume(task);
      return markDead(task, resume, task.getAttemptCount(), message.executionEpoch());
    }));
  }

  private AnalysisWork begin(UUID taskId) {
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

  private AnalysisWork begin(AsyncTaskEntity task) {
    ResumeEntity resume = requireResume(task);
    if (task.getStatus() == AsyncTaskStatus.COMPLETED
        && resume.getStatus() == ResumeStatus.READY) {
      return null;
    }
    if ((task.getStatus() == AsyncTaskStatus.FAILED || task.getStatus() == AsyncTaskStatus.DEAD)
        && resume.getStatus() == ResumeStatus.FAILED) {
      return null;
    }
    if (resume.getStatus() == ResumeStatus.PENDING) {
      resume.setStatus(ResumeStatus.ANALYZING);
    } else if (resume.getStatus() != ResumeStatus.ANALYZING) {
      throw new IllegalStateException("Resume analysis state is inconsistent");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Resume analysis task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    int attemptGeneration = task.getAttemptCount();
    task.setLastError(null);
    resumeRepository.save(resume);
    taskRepository.save(task);
    return new AnalysisWork(
        task.getTaskId(), resume.getId(), resume.getParsedText(), attemptGeneration);
  }

  private Outcome complete(AnalysisWork work, String profileJson) {
    AsyncTaskEntity task = requireTask(work.taskId());
    ResumeEntity resume = resumeRepository.findById(work.resumeId()).orElseThrow();
    if (!isCurrentProcessingAttempt(task, resume, work)) {
      return Outcome.STALE;
    }
    resume.setSkillsSnapshot(profileJson);
    resume.setFailureReason(null);
    resume.setStatus(ResumeStatus.READY);
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.RESUME_ANALYSIS));
    return Outcome.TERMINAL;
  }

  private Outcome fail(AnalysisWork work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    ResumeEntity resume = resumeRepository.findById(work.resumeId()).orElseThrow();
    if (!isCurrentProcessingAttempt(task, resume, work)) {
      return Outcome.STALE;
    }
    resume.setSkillsSnapshot(null);
    resume.setFailureReason(INVALID_PROFILE_ERROR);
    resume.setStatus(ResumeStatus.FAILED);
    task.setStatus(AsyncTaskStatus.FAILED);
    task.setLastError(INVALID_PROFILE_ERROR);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.RESUME_ANALYSIS, "failed"));
    return Outcome.TERMINAL;
  }

  private boolean recordRetryableFailure(AnalysisWork work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    ResumeEntity resume = resumeRepository.findById(work.resumeId()).orElseThrow();
    if (!isCurrentProcessingAttempt(task, resume, work)) {
      return false;
    }
    task.setLastError(RETRYABLE_ERROR);
    return true;
  }

  private void validate(ResumeProfile profile) {
    if (profile == null) {
      throw new ConstraintViolationException(java.util.Set.of());
    }
    var violations = validator.validate(profile);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
  }

  private AsyncTaskEntity requireTask(UUID taskId) {
    return taskRepository.findByTaskId(Objects.requireNonNull(taskId, "taskId"))
        .orElseThrow(() -> new IllegalArgumentException("Resume analysis task not found"));
  }

  private AsyncTaskEntity requireMatchingTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.RESUME_ANALYSIS
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Resume analysis message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Resume analysis message does not match stored task");
    }
    return task;
  }

  private boolean markDead(
      AsyncTaskEntity task,
      ResumeEntity resume,
      int expectedAttemptGeneration,
      int expectedExecutionEpoch) {
    if (task.getExecutionEpoch() != expectedExecutionEpoch
        || task.getAttemptCount() != expectedAttemptGeneration) {
      return false;
    }
    if (task.getStatus() == AsyncTaskStatus.DEAD
        && resume.getStatus() == ResumeStatus.FAILED) return true;
    if ((task.getStatus() != AsyncTaskStatus.PENDING
        && task.getStatus() != AsyncTaskStatus.PUBLISHED)
        || (resume.getStatus() != ResumeStatus.PENDING
        && resume.getStatus() != ResumeStatus.ANALYZING)) {
      return false;
    }
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setLastError("Resume analysis retries exhausted");
    resume.setStatus(ResumeStatus.FAILED);
    resume.setFailureReason("Resume analysis retries exhausted");
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.RESUME_ANALYSIS, "dead"));
    return true;
  }

  private ResumeEntity requireResume(AsyncTaskEntity task) {
    if (task.getTaskType() != AsyncTaskType.RESUME_ANALYSIS) {
      throw new IllegalArgumentException("Task is not a resume analysis task");
    }
    String prefix = "resume:";
    if (task.getBizKey() == null || !task.getBizKey().startsWith(prefix)) {
      throw new IllegalArgumentException("Resume analysis business key is invalid");
    }
    try {
      return resumeRepository.findById(Long.valueOf(task.getBizKey().substring(prefix.length())))
          .orElseThrow(() -> new IllegalArgumentException("Resume not found"));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Resume analysis business key is invalid");
    }
  }

  private boolean isCurrentProcessingAttempt(
      AsyncTaskEntity task,
      ResumeEntity resume,
      AnalysisWork work) {
    return task.getStatus() == AsyncTaskStatus.PUBLISHED
        && resume.getStatus() == ResumeStatus.ANALYZING
        && task.getAttemptCount() == work.attemptGeneration();
  }

  private record AnalysisWork(
      UUID taskId,
      Long resumeId,
      String resumeText,
      int attemptGeneration) {}

  private record BeginResult(AnalysisWork work, boolean stale) {}

  public enum Outcome {
    TERMINAL,
    STALE
  }

  public record ResumeAnalysisTarget(
      Long resumeId, boolean terminal, int attemptGeneration, int executionEpoch) {
    public ResumeAnalysisTarget(Long resumeId, boolean terminal) {
      this(resumeId, terminal, 0, 0);
    }
  }
}
