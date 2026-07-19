package interview.pilot.knowledge.retrieval;

import java.util.UUID;

public record KnowledgeChunk(
    String pointId, UUID documentId, String filename,
    int chunkIndex, double score, String content) {

  public KnowledgeChunk {
    if (pointId == null || pointId.isBlank()) {
      throw new IllegalArgumentException("pointId must not be blank");
    }
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    if (!Double.isFinite(score) || score < 0 || score > 1) {
      throw new IllegalArgumentException("score must be between 0 and 1");
    }
    if (chunkIndex < 0) {
      throw new IllegalArgumentException("chunkIndex must not be negative");
    }
    filename = filename == null ? "" : filename.trim();
  }
}
