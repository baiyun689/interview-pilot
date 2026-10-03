package interview.pilot.voice.application;

/** Carries only the execution identity committed before external I/O. */
public class VoiceTranscriptionRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public VoiceTranscriptionRetryableException(String message, int attemptGeneration) { this(message, attemptGeneration, null, null); }
  public VoiceTranscriptionRetryableException(String message, int attemptGeneration, String executionToken) { this(message, attemptGeneration, executionToken, null); }
  public VoiceTranscriptionRetryableException(String message, int attemptGeneration, String executionToken, Throwable cause) {
    super(message, cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = executionToken;
  }
  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
}
