package interview.pilot.interview.application;

/** Carries the execution actually committed by begin, not the earlier inspection snapshot. */
public class AnswerEvaluationRetryableException extends RuntimeException {
  private final int attemptGeneration;

  public AnswerEvaluationRetryableException(int attemptGeneration, Throwable cause) {
    super("Answer evaluation temporarily unavailable", cause);
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() { return attemptGeneration; }
}
