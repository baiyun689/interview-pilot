package interview.pilot.voice.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import interview.pilot.voice.domain.StoredVoiceMedia;

/**
 * Test-double {@link SpeechRecognizer} (plan §5.3): by default returns a deterministic
 * transcript that echoes the vocabulary size, so end-to-end tests can observe the recognition
 * context that reached the seam. Tests may swap the behavior via {@link #respondWith} (blank
 * transcript, oversized transcript, thrown failures) and restore it with {@link #reset}.
 * Never a production bean.
 */
public class FakeSpeechRecognizer implements SpeechRecognizer {

  private final AtomicReference<Function<RecognitionContext, Transcript>> behavior;

  public FakeSpeechRecognizer() {
    this(FakeSpeechRecognizer::defaultTranscript);
  }

  public FakeSpeechRecognizer(Function<RecognitionContext, Transcript> behavior) {
    this.behavior = new AtomicReference<>(Objects.requireNonNull(behavior, "behavior"));
  }

  public void respondWith(Function<RecognitionContext, Transcript> behavior) {
    this.behavior.set(Objects.requireNonNull(behavior, "behavior"));
  }

  public void reset() {
    behavior.set(FakeSpeechRecognizer::defaultTranscript);
  }

  @Override
  public Transcript transcribe(StoredVoiceMedia audio, RecognitionContext context) {
    return behavior.get().apply(context);
  }

  private static Transcript defaultTranscript(RecognitionContext context) {
    return new Transcript(
        "fake transcript (vocabulary=" + context.vocabulary().size() + ")",
        "fake-request-" + UUID.randomUUID(), "fake-asr-model");
  }
}
