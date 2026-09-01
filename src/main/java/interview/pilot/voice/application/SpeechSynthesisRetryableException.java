package interview.pilot.voice.application;

/**
 * Operational synthesis failure (connection/read timeout, 429, 5xx, media IO): the listener
 * re-queues the message via the delayed-retry pipeline and, after exhaustion, dead-letters it
 * and marks the speech FAILED with the fenced attempt generation (plan §11).
 */
public class SpeechSynthesisRetryableException extends RuntimeException {

  private final int attemptGeneration;

  public SpeechSynthesisRetryableException(String message, int attemptGeneration) {
    super(message);
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }
}
