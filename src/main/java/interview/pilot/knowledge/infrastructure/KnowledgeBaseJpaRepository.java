package interview.pilot.knowledge.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface KnowledgeBaseJpaRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
  Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndUserAccountId(
      UUID knowledgeBaseId, Long userAccountId);
}
