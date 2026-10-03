package interview.pilot.knowledge.indexing;

/** Carries only the execution identity committed before external I/O. */
public class KnowledgeIndexRetryableException extends RuntimeException {
  private final int attemptGeneration;
  private final String executionToken;

  public KnowledgeIndexRetryableException(int attemptGeneration) { this(attemptGeneration, null, null); }
  public KnowledgeIndexRetryableException(int attemptGeneration, String executionToken) { this(attemptGeneration, executionToken, null); }
  public KnowledgeIndexRetryableException(int attemptGeneration, String executionToken, Throwable cause) {
    super("Knowledge indexing temporarily unavailable", cause);
    this.attemptGeneration = attemptGeneration;
    this.executionToken = executionToken;
  }
  public int attemptGeneration() { return attemptGeneration; }
  public String executionToken() { return executionToken; }
}
