package interview.pilot.voice.application;

/**
 * Operational transcription failure (network, timeout, 429, 5xx): the listener routes the
 * message through the RabbitMQ delayed-retry pipeline (plan §10 step 6). {@code attemptGeneration}
 * is the task attempt count at the time of the failure, so a dead-lettered message can never
 * terminalize a newer generation's row.
 */
public class VoiceTranscriptionRetryableException extends RuntimeException {

  private final int attemptGeneration;

  public VoiceTranscriptionRetryableException(String message, int attemptGeneration) {
    super(message);
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }
}
