package interview.pilot.voice.application;

import java.util.UUID;

import org.springframework.http.HttpRange;

import interview.pilot.auth.application.CurrentUser;

/**
 * Question speech seam (plan §5.2): only persisted turns of sessions owned by the current
 * user, one speech row per turn (uq_question_speech_turn). {@code getOrSchedule} returns the
 * existing row's view, schedules the missing one (VOICE session + TTS configured → the same
 * getOrCreate path as turn creation), or answers NOT_AVAILABLE for TEXT sessions and
 * unconfigured TTS — a legitimately existing turn without speech is never a 404. {@code retry}
 * only accepts FAILED speech (mirror of {@code VoiceAnswerModule.retry}): the listener's
 * Redis claim is cleared first, then the speech FAILED → PENDING transition and the task
 * reset run in ONE transaction with their epochs bumped in lockstep, so stale listener
 * messages from the old generation are fenced. {@code open} validates ownership (404 hides
 * cross-user/cross-session resources) and the READY state (409 otherwise) and returns the
 * media resource; a non-null {@link HttpRange} is resolved against the media length
 * ({@link interview.pilot.voice.domain.VoiceRangeNotSatisfiableException} for unsatisfiable
 * ranges) and the returned stream is seeked and bounded to the slice.
 *
 * <p>Cross-user/cross-session resources surface as 404 QUESTION_SPEECH_NOT_FOUND everywhere;
 * 409 signals state conflicts (plan §8.6).
 */
public interface QuestionSpeechModule {

  QuestionSpeechView getOrSchedule(CurrentUser user, UUID sessionId, int turnNo);

  /**
   * READY-only media open. The returned {@link QuestionSpeechMedia} carries the weak etag
   * derived from the speech row's version — the only server-side signal that a re-synthesized
   * generation replaced the content at the immutable storage key.
   */
  QuestionSpeechMedia open(CurrentUser user, UUID sessionId, UUID speechId, HttpRange range);

  void retry(CurrentUser user, UUID sessionId, UUID speechId);
}
