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
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.RetryableTaskPolicyRegistry;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;

/**
 * Generic task lifecycle endpoint: lookup and manual retry. The task-type-specific parts of
 * manual retry (claim key, per-type state recovery or refusal) live in
 * {@link interview.pilot.async.policy.RetryableTaskPolicy policies} routed by
 * {@link RetryableTaskPolicyRegistry} — adding a task type no longer grows a switch here.
 */
@Service
public class AsyncTaskService {
  private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

  private final AsyncTaskRepository tasks;
  private final ProcessingClaim claims;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;
  private final RetryableTaskPolicyRegistry policies;

  public AsyncTaskService(
      AsyncTaskRepository tasks,
      ProcessingClaim claims,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics,
      RetryableTaskPolicyRegistry policies) {
    this.tasks = tasks;
    this.claims = claims;
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.policies = policies;
  }

  public AsyncTaskResponse get(CurrentUser user, UUID taskId) {
    return transactions.execute(status -> response(requireTask(user, taskId)));
  }

  public AsyncTaskResponse retry(CurrentUser user, UUID taskId, UUID traceId) {
    RetryTarget target = transactions.execute(status -> retryTarget(user, taskId));
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

  private RetryTarget retryTarget(CurrentUser user, UUID taskId) {
    AsyncTaskEntity task = requireTask(user, taskId);
    requireRetryable(task);
    return new RetryTarget(task.getId(), requireOwner(user), task.getVersion(),
        policies.forType(task.getTaskType()).claimKey(task));
  }

  private AsyncTaskResponse reset(RetryTarget target) {
    AsyncTaskEntity task = tasks.findByIdAndUserAccountId(target.databaseId(), target.userAccountId())
        .orElseThrow(() -> notFound());
    if (task.getVersion() != target.version()) {
      metrics.optimisticLockConflict();
      throw conflict("TASK_RETRY_CONFLICT", "Task retry conflicted with another request");
    }
    requireRetryable(task);
    policies.forType(task.getTaskType()).reset(task, target.userAccountId());
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

  private AsyncTaskEntity requireTask(CurrentUser user, UUID taskId) {
    return tasks.findByTaskIdAndUserAccountId(taskId, requireOwner(user)).orElseThrow(this::notFound);
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
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

  private record RetryTarget(Long databaseId, Long userAccountId, long version, String claimKey) {}
}
