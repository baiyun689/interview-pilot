package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {
  @Query("""
      select document from KnowledgeDocumentEntity document
      join document.knowledgeBase knowledgeBase
      where knowledgeBase.knowledgeBaseId in :knowledgeBaseIds
        and knowledgeBase.userAccountId = :userAccountId
        and document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY
      order by document.createdAt desc
      """)
  List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      @Param("knowledgeBaseIds") Collection<UUID> knowledgeBaseIds,
      @Param("userAccountId") Long userAccountId);
}
