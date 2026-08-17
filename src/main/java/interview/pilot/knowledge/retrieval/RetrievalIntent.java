package interview.pilot.knowledge.retrieval;

import java.util.List;

public record RetrievalIntent(
    String query, String competency, String difficulty,
    List<String> keywords, List<String> excludeTopics,
    int topK, int candidateCount, double similarityThreshold,
    int contextCharacterBudget) {

  public RetrievalIntent {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be positive");
    }
    if (candidateCount < topK) throw new IllegalArgumentException("candidateCount must be at least topK");
    if (contextCharacterBudget < 100) {
      throw new IllegalArgumentException("contextCharacterBudget must be at least 100");
    }
    if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
      throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
    }
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
    excludeTopics = excludeTopics == null ? List.of() : List.copyOf(excludeTopics);
  }

  public RetrievalIntent(
      String query, String competency, String difficulty,
      List<String> keywords, List<String> excludeTopics,
      int topK, double similarityThreshold) {
    this(query, competency, difficulty, keywords, excludeTopics,
        topK, Math.max(12, topK * 3), similarityThreshold, 6_000);
  }
}
