package interview.pilot.voice.application;

/**
 * Text-to-speech port (plan §5.3): the seam the synthesis listener reaches the provider
 * adapter through. Adapters: {@code DashScopeSpeechSynthesizer} (production) and
 * {@code FakeSpeechSynthesizer} (tests). No provider structure appears outside the adapters.
 *
 * <p>Failure contract the listener routes on: operational failures (network/timeout/429/5xx)
 * throw {@link SpeechSynthesisRetryableException} so the message re-queues through the
 * delayed-retry pipeline; deterministic failures (4xx rejection, empty or unsupported audio)
 * throw {@link SpeechSynthesisFailedException} so the speech row becomes FAILED and the task
 * is terminal.
 */
public interface SpeechSynthesizer {
  SynthesizedSpeech synthesize(String text, VoiceProfile profile);
}
