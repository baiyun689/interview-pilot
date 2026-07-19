package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.List;

public record RetrievedKnowledge(
    RetrievalStatus status, String query, String embeddingModel,
    List<KnowledgeChunk> chunks, Duration latency, String failureReason) {

  public RetrievedKnowledge {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    if (status == RetrievalStatus.RETRIEVED && chunks.isEmpty()) {
      throw new IllegalArgumentException("retrieved status requires at least one chunk");
    }
    latency = latency == null ? Duration.ZERO : latency;
    failureReason = (failureReason == null || failureReason.isBlank()) ? null : failureReason.trim();
  }

  public static RetrievedKnowledge noMatch(String query, String embeddingModel, Duration latency) {
    return new RetrievedKnowledge(
        RetrievalStatus.NO_MATCH, query, embeddingModel, List.of(), latency, null);
  }

  public static RetrievedKnowledge unavailable(String query, String embeddingModel,
      String failureReason, Duration latency) {
    return new RetrievedKnowledge(
        RetrievalStatus.UNAVAILABLE, query, embeddingModel, List.of(), latency,
        failureReason == null ? "unknown" : failureReason);
  }
}
