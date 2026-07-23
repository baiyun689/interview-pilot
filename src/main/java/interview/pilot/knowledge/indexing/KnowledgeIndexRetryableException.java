package interview.pilot.knowledge.indexing;

public class KnowledgeIndexRetryableException extends RuntimeException {
  private final int attemptGeneration;

  public KnowledgeIndexRetryableException(int attemptGeneration) {
    this.attemptGeneration = attemptGeneration;
  }

  public int attemptGeneration() {
    return attemptGeneration;
  }
}
