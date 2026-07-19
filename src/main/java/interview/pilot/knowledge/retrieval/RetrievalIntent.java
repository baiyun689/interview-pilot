package interview.pilot.knowledge.retrieval;

import java.util.List;

public record RetrievalIntent(
    String query, String competency, String difficulty,
    List<String> keywords, List<String> excludeTopics,
    int topK, double similarityThreshold) {

  public RetrievalIntent {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be positive");
    }
    if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
      throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
    }
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
    excludeTopics = excludeTopics == null ? List.of() : List.copyOf(excludeTopics);
  }
}
