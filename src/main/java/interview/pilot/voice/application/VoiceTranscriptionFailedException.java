package interview.pilot.voice.application;

/**
 * Deterministic transcription failure (4xx rejection, empty transcript, unparseable response):
 * the recording becomes FAILED with {@code VOICE_TRANSCRIPTION_FAILED} and the task is
 * terminal — retrying the same request would fail again (plan §10 step 7).
 */
public class VoiceTranscriptionFailedException extends RuntimeException {

  public VoiceTranscriptionFailedException(String message) {
    super(message);
  }
}
