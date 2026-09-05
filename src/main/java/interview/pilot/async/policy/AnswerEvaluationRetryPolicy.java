package interview.pilot.async.policy;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;

/**
 * Manual-retry policy for ANSWER_EVALUATION. The generic task-retry endpoint refuses to reset it:
 * per-turn evaluation is a derived, non-critical artifact — a turn is answered exactly once, so a
 * fresh answer always produces a fresh turn and a fresh evaluation task, never a reset of an old
 * one. Runtime retries (5s/30s/120s delayed requeue then DLQ) are still handled by the shared
 * {@code TaskRetryPolicy}; on exhaustion the handler marks the turn's eval_status FAILED without
 * touching the answer flow.
 *
 * <p>The claim key equals the bizKey ({@code answer-eval:<sessionId>:<turnNo>}), which is also the
 * key the evaluation listener acquires, so retry bookkeeping and processing claims never drift.
 */
@Component
public class AnswerEvaluationRetryPolicy extends AbstractRetryableTaskPolicy {
  public static final String BIZ_KEY_PREFIX = "answer-eval:";

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.ANSWER_EVALUATION;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return task.getBizKey();
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    throw conflict("TASK_NOT_RETRYABLE", "Answer evaluation is derived from the answered turn");
  }
}
