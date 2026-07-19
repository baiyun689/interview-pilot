package interview.pilot.knowledge.retrieval;

import java.util.List;
import java.util.UUID;

public record ValidatedKnowledgeScope(
    UUID userId,
    List<UUID> knowledgeBaseIds,
    List<DocumentRevision> documents,
    String embeddingVersion) {

  public ValidatedKnowledgeScope {
    if (userId == null) {
      throw new IllegalArgumentException("userId must not be null");
    }
    knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    if (knowledgeBaseIds.isEmpty()) {
      throw new IllegalArgumentException("knowledgeBaseIds must not be empty");
    }
    documents = List.copyOf(documents);
    if (documents.isEmpty()) {
      throw new IllegalArgumentException("documents must not be empty");
    }
    if (embeddingVersion == null || embeddingVersion.isBlank()) {
      throw new IllegalArgumentException("embeddingVersion must not be blank");
    }
  }

  public record DocumentRevision(UUID documentId, int indexRevision) {
    public DocumentRevision {
      if (documentId == null) {
        throw new IllegalArgumentException("documentId must not be null");
      }
      if (indexRevision < 1) {
        throw new IllegalArgumentException("indexRevision must be positive");
      }
    }
  }
}
