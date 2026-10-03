package interview.pilot.interview.application;

/** Carries only the execution identity committed before external I/O. */
public class ReportGenerationRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public ReportGenerationRetryableException(int attemptGeneration) { this(attemptGeneration, null, null); }
  public ReportGenerationRetryableException(int attemptGeneration, String executionToken) { this(attemptGeneration, executionToken, null); }
  public ReportGenerationRetryableException(int attemptGeneration, String executionToken, Throwable cause) {
    super("Interview report generation temporarily unavailable", cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = executionToken;
  }
  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
}
