package interview.pilot.knowledge.api;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocumentResponse(
    UUID documentId,
    String originalFilename,
    String status,
    int indexRevision,
    int chunkCount,
    String failureReason,
    Instant createdAt) {

  public static KnowledgeDocumentResponse processing(UUID documentId) {
    return new KnowledgeDocumentResponse(
        documentId, null, "PROCESSING", 1, 0, null, null);
  }
}
