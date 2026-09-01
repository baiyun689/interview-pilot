package interview.pilot.voice.application;

import java.util.Objects;

/**
 * Result of a {@link SpeechSynthesizer} call: the generated audio bytes (TTS output is
 * provider-generated and small — a few hundred KB — so it is read fully into memory and then
 * streamed into the store, plan §11), the provider's reported media type (informational; the
 * store's probe is authoritative) and the provider request id when the endpoint exposes one
 * (the non-realtime binary response does not — {@code providerRequestId} stays null).
 */
public record SynthesizedSpeech(
    byte[] audio, String mediaType, String providerRequestId) {

  public SynthesizedSpeech {
    Objects.requireNonNull(audio, "audio");
  }
}
