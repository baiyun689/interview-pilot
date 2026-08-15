package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeBaseJpaRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
  Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndUserAccountId(
      UUID knowledgeBaseId, Long userAccountId);

  List<KnowledgeBaseEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);

  @Modifying
  @Query("""
      delete from KnowledgeBaseEntity kb
      where kb.knowledgeBaseId = :knowledgeBaseId and kb.userAccountId = :userAccountId
      """)
  void deleteByKnowledgeBaseIdAndUserAccountId(
      @Param("knowledgeBaseId") UUID knowledgeBaseId,
      @Param("userAccountId") Long userAccountId);
}
