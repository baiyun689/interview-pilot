package interview.pilot.knowledge.retrieval;

import java.util.UUID;

public record KnowledgeChunk(
    String pointId, UUID documentId, String filename,
    int documentRevision, int chunkIndex, String section,
    double score, String content, Integer pageNumber) {

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
    if (documentRevision < 1) {
      throw new IllegalArgumentException("documentRevision must be positive");
    }
    section = section == null ? "" : section.trim();
    if (pageNumber != null && pageNumber < 1) {
      throw new IllegalArgumentException("pageNumber must be positive");
    }
  }

  public KnowledgeChunk(
      String pointId, UUID documentId, String filename,
      int chunkIndex, double score, String content) {
    this(pointId, documentId, filename, 1, chunkIndex, "", score, content, null);
  }
}
