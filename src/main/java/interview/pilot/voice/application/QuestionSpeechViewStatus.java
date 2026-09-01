package interview.pilot.voice.application;

/**
 * Client-facing question speech status (plan §8.5). Mirrors the persisted
 * {@code QuestionSpeechStatus} plus the view-only {@link #NOT_AVAILABLE}: a turn whose speech
 * can never be scheduled (TEXT session or TTS unconfigured) is a normal, cacheable 200 answer,
 * never a 404 — the question text is always the authoritative content and speech is a
 * degradable playback capability (plan §5.2/§11).
 */
public enum QuestionSpeechViewStatus {
  PENDING,
  SYNTHESIZING,
  READY,
  FAILED,
  NOT_AVAILABLE
}
