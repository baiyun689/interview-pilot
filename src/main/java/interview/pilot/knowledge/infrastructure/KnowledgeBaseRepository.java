package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
  Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndUserAccountId(
      UUID knowledgeBaseId, Long userAccountId);

  List<KnowledgeBaseEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);
}
