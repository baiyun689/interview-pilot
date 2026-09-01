package interview.pilot.voice.application;

/**
 * Deterministic synthesis failure (4xx rejection, empty or unsupported audio): the speech
 * becomes FAILED with {@code VOICE_QUESTION_SPEECH_FAILED} and the task is terminal —
 * retrying the same request would fail again (plan §11). A FAILED speech never touches the
 * interview: the question text stays fully answerable.
 */
public class SpeechSynthesisFailedException extends RuntimeException {

  public SpeechSynthesisFailedException(String message) {
    super(message);
  }
}
