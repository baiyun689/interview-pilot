package interview.pilot.async.policy;

import org.springframework.http.HttpStatus;

import interview.pilot.common.exception.BusinessException;

/**
 * Shared error construction for the concrete policies. The error codes and messages mirror
 * exactly what the centralized switch in {@code AsyncTaskService} produced before the
 * registry refactor (Task R): parsing failures and business-state mismatches surface as
 * {@code TASK_STATE_INVALID} 409s.
 */
abstract class AbstractRetryableTaskPolicy implements RetryableTaskPolicy {
  protected static BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  protected static BusinessException stateInvalid() {
    return conflict("TASK_STATE_INVALID", "Task state is inconsistent");
  }
}
