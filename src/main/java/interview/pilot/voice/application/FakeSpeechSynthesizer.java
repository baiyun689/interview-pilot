package interview.pilot.voice.application;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Test-double {@link SpeechSynthesizer} (plan §5.3): by default returns deterministic audio
 * bytes that echo the synthesized text, so end-to-end tests can observe the question text
 * that reached the seam. Tests may swap the behavior via {@link #respondWith} (empty audio,
 * thrown failures) and restore it with {@link #reset}. Never a production bean.
 */
public class FakeSpeechSynthesizer implements SpeechSynthesizer {

  private final AtomicReference<BiFunction<String, VoiceProfile, SynthesizedSpeech>> behavior;

  public FakeSpeechSynthesizer() {
    this((text, profile) -> new SynthesizedSpeech(
        ("fake-audio:" + text).getBytes(StandardCharsets.UTF_8),
        "audio/mpeg", "fake-request-" + UUID.randomUUID()));
  }

  public FakeSpeechSynthesizer(
      BiFunction<String, VoiceProfile, SynthesizedSpeech> behavior) {
    this.behavior = new AtomicReference<>(Objects.requireNonNull(behavior, "behavior"));
  }

  public void respondWith(BiFunction<String, VoiceProfile, SynthesizedSpeech> behavior) {
    this.behavior.set(Objects.requireNonNull(behavior, "behavior"));
  }

  public void reset() {
    behavior.set((text, profile) -> new SynthesizedSpeech(
        ("fake-audio:" + text).getBytes(StandardCharsets.UTF_8),
        "audio/mpeg", "fake-request-" + UUID.randomUUID()));
  }

  @Override
  public SynthesizedSpeech synthesize(String text, VoiceProfile profile) {
    return behavior.get().apply(text, profile);
  }
}
