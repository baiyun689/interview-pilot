package interview.pilot.interview.application;

public class ReportGenerationRetryableException extends RuntimeException {
  private final int attemptGeneration;

  public ReportGenerationRetryableException(int attemptGeneration) {
    super("Interview report generation temporarily unavailable");
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }
}
