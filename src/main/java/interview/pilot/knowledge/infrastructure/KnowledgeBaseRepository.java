package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository {
  KnowledgeBaseEntity save(KnowledgeBaseEntity knowledgeBase);

  Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndUserAccountId(
      UUID knowledgeBaseId, Long userAccountId);

  List<KnowledgeBaseEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);
}
