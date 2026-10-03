package interview.pilot.interview.application;

/** Identifies the committed execution even when generation or its final write fails. */
public class QuestionPreparationExecutionException extends RuntimeException {
  private final int attemptGeneration;

  public QuestionPreparationExecutionException(int attemptGeneration, RuntimeException cause) {
    super("Question preparation failed", cause);
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() { return attemptGeneration; }
}
