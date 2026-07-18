package interview.pilot.async.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.api.AsyncTaskResponse;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeRepository;

@Service
public class AsyncTaskService {
  private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

  private final AsyncTaskRepository tasks;
  private final ResumeRepository resumes;
  private final InterviewSessionRepository sessions;
  private final ProcessingClaim claims;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;

  public AsyncTaskService(
      AsyncTaskRepository tasks,
      ResumeRepository resumes,
      InterviewSessionRepository sessions,
      ProcessingClaim claims,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.tasks = tasks;
    this.resumes = resumes;
    this.sessions = sessions;
    this.claims = claims;
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
  }

  public AsyncTaskResponse get(UUID taskId) {
    return transactions.execute(status -> response(requireTask(taskId)));
  }

  public AsyncTaskResponse retry(UUID taskId, UUID traceId) {
    RetryTarget target = transactions.execute(status -> retryTarget(taskId));
    ProcessingClaim.ClearResult cleared;
    try {
      cleared = claims.clearTerminal(target.claimKey());
    } catch (RuntimeException exception) {
      throw conflict("TASK_RETRY_UNAVAILABLE", "Task retry is temporarily unavailable");
    }
    if (cleared == ProcessingClaim.ClearResult.ACTIVE) {
      throw conflict("TASK_STILL_PROCESSING", "Task processing is still active");
    }
    try {
      AsyncTaskResponse result = transactions.execute(status -> reset(target));
      log.info("async_task_retry taskId={} taskType={} traceId={}",
          result.taskId(), result.taskType(), traceId);
      return result;
    } catch (OptimisticLockingFailureException exception) {
      metrics.optimisticLockConflict();
      throw conflict("TASK_RETRY_CONFLICT", "Task retry conflicted with another request");
    }
  }

  private RetryTarget retryTarget(UUID taskId) {
    AsyncTaskEntity task = requireTask(taskId);
    requireRetryable(task);
    return new RetryTarget(task.getId(), task.getVersion(), claimKey(task));
  }

  private AsyncTaskResponse reset(RetryTarget target) {
    AsyncTaskEntity task = tasks.findById(target.databaseId())
        .orElseThrow(() -> notFound());
    if (task.getVersion() != target.version()) {
      metrics.optimisticLockConflict();
      throw conflict("TASK_RETRY_CONFLICT", "Task retry conflicted with another request");
    }
    requireRetryable(task);
    if (task.getTaskType() == AsyncTaskType.RESUME_ANALYSIS) {
      Long resumeId = parseResumeId(task.getBizKey());
      var resume = resumes.findById(resumeId)
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (resume.getStatus() != ResumeStatus.FAILED) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
      resume.setStatus(ResumeStatus.PENDING);
      resume.setFailureReason(null);
      resume.setSkillsSnapshot(null);
    } else {
      UUID sessionId = parseInterviewId(task.getBizKey());
      var session = sessions.findBySessionId(sessionId)
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (session.getStatus() != SessionStatus.EVALUATING) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
    }
    task.setStatus(AsyncTaskStatus.PENDING);
    task.setExecutionEpoch(task.getExecutionEpoch() + 1);
    task.setLastPublishedAt(null);
    task.setLastError(null);
    return response(tasks.saveAndFlush(task));
  }

  private void requireRetryable(AsyncTaskEntity task) {
    if (task.getStatus() != AsyncTaskStatus.FAILED
        && task.getStatus() != AsyncTaskStatus.DEAD) {
      throw conflict("TASK_NOT_RETRYABLE", "Only failed or dead tasks can be retried");
    }
  }

  private String claimKey(AsyncTaskEntity task) {
    return switch (task.getTaskType()) {
      case RESUME_ANALYSIS -> "resume-analysis:" + parseResumeId(task.getBizKey());
      case INTERVIEW_EVALUATION -> "interview-report:" + parseInterviewId(task.getBizKey());
    };
  }

  private Long parseResumeId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("resume:")) throw new IllegalArgumentException();
      return Long.valueOf(bizKey.substring("resume:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private UUID parseInterviewId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("interview:")) throw new IllegalArgumentException();
      return UUID.fromString(bizKey.substring("interview:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private AsyncTaskEntity requireTask(UUID taskId) {
    return tasks.findByTaskId(taskId).orElseThrow(this::notFound);
  }

  private AsyncTaskResponse response(AsyncTaskEntity task) {
    return new AsyncTaskResponse(
        task.getTaskId(), task.getTaskType(), task.getStatus(), task.getAttemptCount(),
        task.getPublishAttempts(), task.getLastError(), task.getCreatedAt(), task.getUpdatedAt());
  }

  private BusinessException notFound() {
    return new BusinessException("TASK_NOT_FOUND", "Async task not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private record RetryTarget(Long databaseId, long version, String claimKey) {}
}
