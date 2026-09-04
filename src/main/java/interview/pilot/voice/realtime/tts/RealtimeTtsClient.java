package interview.pilot.voice.realtime.tts;

/**
 * Realtime text-to-speech client. Synthesis is whole-utterance: the caller blocks until the
 * complete PCM (s16le mono) is available or synthesis fails, which matches the "one question at a
 * time" interview turn model and keeps browser playback trivial.
 */
public interface RealtimeTtsClient {

  /** Synthesize one utterance. Returns an empty array (never null) on blank text or failure. */
  byte[] synthesize(String text);

  /** PCM sample rate of the returned bytes (used to build the WAV header). */
  int sampleRate();

  /** Whether synthesis is usable; false lets the pipeline degrade to text-only gracefully. */
  boolean available();
}
