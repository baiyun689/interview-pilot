package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository {
  KnowledgeDocumentEntity save(KnowledgeDocumentEntity document);

  Optional<KnowledgeDocumentEntity> findByDocumentId(UUID documentId);

  Optional<KnowledgeDocumentEntity> findByDocumentIdWithKnowledgeBase(UUID documentId);

  long countByKnowledgeBaseIdAndStatus(Long knowledgeBaseId,
      interview.pilot.knowledge.domain.KnowledgeDocumentStatus status);

  List<KnowledgeDocumentEntity> findVisibleByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId);

  List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId);
}
