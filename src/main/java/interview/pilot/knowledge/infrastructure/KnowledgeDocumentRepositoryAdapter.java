package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class KnowledgeDocumentRepositoryAdapter implements KnowledgeDocumentRepository {
  private final KnowledgeDocumentJpaRepository delegate;

  KnowledgeDocumentRepositoryAdapter(KnowledgeDocumentJpaRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public KnowledgeDocumentEntity save(KnowledgeDocumentEntity document) {
    return delegate.save(document);
  }

  @Override
  public Optional<KnowledgeDocumentEntity> findByDocumentId(UUID documentId) {
    return delegate.findByDocumentId(documentId);
  }

  @Override
  public Optional<KnowledgeDocumentEntity> findByDocumentIdWithKnowledgeBase(UUID documentId) {
    return delegate.findByDocumentIdWithKnowledgeBase(documentId);
  }

  @Override
  public long countByKnowledgeBaseIdAndStatus(Long knowledgeBaseId,
      interview.pilot.knowledge.domain.KnowledgeDocumentStatus status) {
    return delegate.countByKnowledgeBaseIdAndStatus(knowledgeBaseId, status);
  }

  @Override
  public List<KnowledgeDocumentEntity> findVisibleByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId) {
    return delegate.findVisibleByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, userAccountId);
  }

  @Override
  public List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId) {
    return delegate.findReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, userAccountId);
  }

  @Override
  public List<KnowledgeDocumentEntity> lockReadyByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId) {
    return delegate.lockReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, userAccountId);
  }

  @Override
  public List<KnowledgeDocumentEntity> findAllByKnowledgeBase(KnowledgeBaseEntity knowledgeBase) {
    return delegate.findAllByKnowledgeBase(knowledgeBase);
  }

  @Override
  public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
    delegate.deleteByKnowledgeBaseId(knowledgeBaseId);
  }
}
