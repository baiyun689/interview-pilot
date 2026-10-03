package interview.pilot.voice.application;

/** Carries only the execution identity committed before external I/O. */
public class SpeechSynthesisRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public SpeechSynthesisRetryableException(String message, int attemptGeneration) { this(message, attemptGeneration, null, null); }
  public SpeechSynthesisRetryableException(String message, int attemptGeneration, String executionToken) { this(message, attemptGeneration, executionToken, null); }
  public SpeechSynthesisRetryableException(String message, int attemptGeneration, String executionToken, Throwable cause) {
    super(message, cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = executionToken;
  }
  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
}
