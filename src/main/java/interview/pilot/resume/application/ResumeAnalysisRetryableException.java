package interview.pilot.resume.application;

public class ResumeAnalysisRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public ResumeAnalysisRetryableException(int attemptGeneration, String executionToken) {
    super("Resume analysis temporarily unavailable");
    this.attemptGeneration = attemptGeneration;
    this.executionToken = java.util.Objects.requireNonNull(executionToken, "executionToken");
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }

  public String executionToken() { return executionToken; }
}
