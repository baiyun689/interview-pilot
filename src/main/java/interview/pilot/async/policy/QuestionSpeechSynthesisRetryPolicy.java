package interview.pilot.async.policy;

import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;

/**
 * Manual retry for QUESTION_SPEECH_SYNTHESIS.
 *
 * <p>The generic endpoint refuses to reset the task row on purpose, mirroring
 * {@link VoiceTranscriptionRetryPolicy}: a question-speech task is the async half of a
 * question_speech row, and the two rows must stay in epoch lockstep. The speech retry
 * endpoint (Task 8) is the only path that resets both — it validates the speech state,
 * fences the FAILED → PENDING transition, and bumps the task epoch in the same transaction.
 * If this endpoint reset the task alone, a listener message from the new task epoch could
 * land on a speech row the user already re-synthesized.
 *
 * <p>{@link #BIZ_KEY_PREFIX} is the single source of truth for the task bizKey format that
 * {@code QuestionSpeechTaskCreator} writes when it creates the task row (Task 7), and the
 * listener/claim-key contract: the claim key IS the bizKey, so a manual retry clears exactly
 * the claim the listener acquires.
 */
@Component
public class QuestionSpeechSynthesisRetryPolicy extends AbstractRetryableTaskPolicy {
  public static final String BIZ_KEY_PREFIX = "question-speech:";
  // The voice claim key IS the bizKey: the speech id fences both the creation path and the
  // listener-side processing claim, so retry clears exactly the claim the listener acquires.
  private static final String CLAIM_KEY_PREFIX = BIZ_KEY_PREFIX;

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.QUESTION_SPEECH_SYNTHESIS;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return CLAIM_KEY_PREFIX + parseSpeechId(task.getBizKey());
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    throw conflict("TASK_NOT_RETRYABLE", "Voice synthesis retry is managed by the question speech");
  }

  private UUID parseSpeechId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith(BIZ_KEY_PREFIX)) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(bizKey.substring(BIZ_KEY_PREFIX.length()));
    } catch (IllegalArgumentException exception) {
      throw stateInvalid();
    }
  }
}
