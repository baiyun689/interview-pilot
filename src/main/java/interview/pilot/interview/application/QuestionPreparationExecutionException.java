package interview.pilot.interview.application;

/** Identifies the committed execution even when generation or its final write fails. */
public class QuestionPreparationExecutionException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public QuestionPreparationExecutionException(int attemptGeneration, String executionToken, RuntimeException cause) {
    super("Question preparation failed", cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = java.util.Objects.requireNonNull(executionToken, "executionToken");
  }

  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
}
