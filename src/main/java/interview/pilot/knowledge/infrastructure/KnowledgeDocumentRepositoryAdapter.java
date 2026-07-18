package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
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
  public List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId) {
    return delegate.findReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, userAccountId);
  }
}
