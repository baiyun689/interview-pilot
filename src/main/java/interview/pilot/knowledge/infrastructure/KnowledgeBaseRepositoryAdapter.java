package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class KnowledgeBaseRepositoryAdapter implements KnowledgeBaseRepository {
  private final KnowledgeBaseJpaRepository delegate;

  KnowledgeBaseRepositoryAdapter(KnowledgeBaseJpaRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public KnowledgeBaseEntity save(KnowledgeBaseEntity knowledgeBase) {
    return delegate.save(knowledgeBase);
  }

  @Override
  public Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndUserAccountId(
      UUID knowledgeBaseId, Long userAccountId) {
    return delegate.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, userAccountId);
  }

  @Override
  public List<KnowledgeBaseEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId) {
    return delegate.findAllByUserAccountIdOrderByCreatedAtDesc(userAccountId);
  }
}
