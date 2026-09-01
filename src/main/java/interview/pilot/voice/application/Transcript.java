package interview.pilot.voice.application;

import java.util.Objects;

/**
 * Recognition result (plan §10 step 4): the transcript text, the provider's request id and
 * the model that actually produced the text (the adapter's model is authoritative — the
 * listener persists it onto the recording row). {@code providerRequestId} may be null when a
 * provider returns none.
 */
public record Transcript(String text, String providerRequestId, String model) {

  public Transcript {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(model, "model");
  }
}
