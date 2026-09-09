package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.UUID;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

public interface KnowledgeChunkRepository {
  List<KnowledgeChunkEntity> search(
      ValidatedKnowledgeScope scope, String query, int candidateCount);

  /** Replaces every chunk of one document revision atomically (idempotent re-index). */
  void replaceRevision(
      UUID documentId, int indexRevision, List<KnowledgeChunkEntity> chunks);

  void deleteByDocumentIdAndIndexRevision(UUID documentId, int indexRevision);

  void deleteByDocumentId(UUID documentId);
}
