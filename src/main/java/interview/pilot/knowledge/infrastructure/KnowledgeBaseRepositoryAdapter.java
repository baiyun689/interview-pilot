package interview.pilot.knowledge.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(EntityManagerFactory.class)
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
}
