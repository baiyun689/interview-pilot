package interview.pilot.async.policy;

import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;

/**
 * Manual retry for VOICE_TRANSCRIPTION.
 *
 * <p>The generic endpoint refuses to reset the task row on purpose. A voice transcription
 * task is the async half of a voice_recording row, and the two rows must stay in epoch
 * lockstep (V9 fenced epoch): {@code VoiceAnswerModule.retry} is the only path that resets
 * both — it validates the recording state, fences {@code recording.beginTranscription()},
 * and bumps the task epoch in the same transaction. If this endpoint reset the task alone, a
 * listener message from the new task epoch could land on a recording the user already
 * discarded or re-recorded. Task 5 owns the listener-side semantics.
 *
 * <p>{@link #BIZ_KEY_PREFIX} is the single source of truth for the task bizKey format that
 * {@code VoiceAnswerServiceImpl} writes when it creates the task row (Task 4 review M4:
 * previously duplicated in the service, the parser, and the tests).
 */
@Component
public class VoiceTranscriptionRetryPolicy extends AbstractRetryableTaskPolicy {
  public static final String BIZ_KEY_PREFIX = "voice-recording:";
  private static final String CLAIM_KEY_PREFIX = "voice-recording:";

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.VOICE_TRANSCRIPTION;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return CLAIM_KEY_PREFIX + parseVoiceRecordingId(task.getBizKey());
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    throw conflict("TASK_NOT_RETRYABLE", "Voice transcription retry is managed by the recording");
  }

  private UUID parseVoiceRecordingId(String bizKey) {
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
