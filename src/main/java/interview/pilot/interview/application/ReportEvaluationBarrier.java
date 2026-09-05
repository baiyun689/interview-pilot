package interview.pilot.interview.application;

import java.util.Collection;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.EvalStatus;

/**
 * Pure decision for the report's bounded wait on asynchronous per-turn evaluations. When the last
 * answer is submitted, the ANSWER_EVALUATION tasks and the INTERVIEW_EVALUATION report task are
 * created together, so a report attempt may start while some turns are still PENDING: it should
 * ride the existing 5s/30s/120s delayed-retry ladder, but only for a bounded number of retries,
 * after which the report proceeds — terminal evaluations are aggregated and still-pending/failed
 * turns fall back to raw question/answer text. The counter is the message's completed-retry count
 * (a non-rollbackable MQ header), NOT the database attempt count, which rolls back when the
 * barrier rethrows and would otherwise stay stuck at zero forever.
 */
@Component
public class ReportEvaluationBarrier {

  public boolean hasPendingEvaluation(Collection<EvalStatus> formalTurnStatuses) {
    return formalTurnStatuses.stream().anyMatch(status -> status == EvalStatus.PENDING);
  }

  /**
   * @param completedRetries number of delayed retries already consumed by THIS report message
   *                         (0 on first delivery, then 1/2/3 as it re-enters through the ladder)
   * @param maxAwaitRetries  consume at most this many delayed retries waiting; on the next attempt
   *                         the report proceeds even if a turn evaluation remains PENDING
   * @return true when the report should requeue and keep waiting; false when it may proceed
   */
  public boolean shouldAwait(
      Collection<EvalStatus> formalTurnStatuses, int completedRetries, int maxAwaitRetries) {
    return hasPendingEvaluation(formalTurnStatuses)
        && completedRetries < Math.max(0, maxAwaitRetries);
  }
}
