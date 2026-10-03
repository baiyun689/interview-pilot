package interview.pilot.interview.application;

import java.util.Objects;
import java.util.UUID;

/** Carries the execution actually committed by begin, not the earlier inspection snapshot. */
public class AnswerEvaluationRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;
  private final UUID requestId;

  public AnswerEvaluationRetryableException(int attemptGeneration, String executionToken, UUID requestId, Throwable cause) {
    super("Answer evaluation temporarily unavailable", cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = Objects.requireNonNull(executionToken, "executionToken");
    this.requestId = Objects.requireNonNull(requestId, "requestId");
  }

  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
  public UUID requestId() { return requestId; }
}
