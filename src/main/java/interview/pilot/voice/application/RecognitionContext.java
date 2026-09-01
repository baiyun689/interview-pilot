package interview.pilot.voice.application;

import java.util.List;
import java.util.Objects;

/**
 * Vocabulary hints for a recognition request (plan §10): job title, JD technical terms,
 * resume technical terms and the fixed Java-backend word list. These are HINTS, not
 * instructions — an adapter that cannot honor them must ignore them silently and never
 * change the semantics of the transcription.
 */
public record RecognitionContext(List<String> vocabulary) {

  public RecognitionContext {
    vocabulary = vocabulary == null ? List.of() : List.copyOf(vocabulary);
  }

  public static RecognitionContext empty() {
    return new RecognitionContext(List.of());
  }
}
