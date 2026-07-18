package interview.pilot.resume.application;

public class ResumeAnalysisRetryableException extends RuntimeException {
  private final int attemptGeneration;

  public ResumeAnalysisRetryableException(int attemptGeneration) {
    super("Resume analysis temporarily unavailable");
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }
}
