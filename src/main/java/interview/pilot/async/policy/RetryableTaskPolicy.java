package interview.pilot.async.policy;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;

/**
 * Task-type-specific manual-retry semantics for the generic task retry endpoint (plan §12).
 *
 * <p>Each task type owns two behaviors that used to live in {@code AsyncTaskService}'s
 * growing type switch:
 *
 * <ul>
 *   <li>{@link #claimKey(AsyncTaskEntity)} — the idempotency business key whose Redis
 *       processing claim must be cleared before the task row is reset;</li>
 *   <li>{@link #reset(AsyncTaskEntity, long)} — the per-type state recovery that makes the
 *       underlying business row retryable, or the refusal to reset when the type owns its
 *       retry elsewhere (e.g. {@code VOICE_TRANSCRIPTION}).</li>
 * </ul>
 *
 * <p>Adding a new task type is a single-point change: implement this interface as a Spring
 * {@code @Component} and the {@link RetryableTaskPolicyRegistry} picks it up automatically.
 * No switch in {@code AsyncTaskService} grows.
 *
 * <p>The plan §12 sketch also listed an {@code inspect(...)} step; it is intentionally
 * absent here. Per-type inspection already lives in the listener-side handlers (e.g. {@code
 * ResumeAnalysisHandler.inspect}), while this policy serves only the generic manual-retry
 * endpoint — importing inspection here would duplicate the handlers' terminal-state logic.
 */
public interface RetryableTaskPolicy {
  AsyncTaskType type();

  String claimKey(AsyncTaskEntity task);

  void reset(AsyncTaskEntity task, long userAccountId);
}
