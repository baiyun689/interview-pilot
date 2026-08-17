package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface KnowledgeDocumentJpaRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {
  Optional<KnowledgeDocumentEntity> findByDocumentId(UUID documentId);

  long countByKnowledgeBaseIdAndStatus(Long knowledgeBaseId,
      interview.pilot.knowledge.domain.KnowledgeDocumentStatus status);

  List<KnowledgeDocumentEntity> findAllByKnowledgeBase(KnowledgeBaseEntity knowledgeBase);

  @Modifying
  @Query("delete from KnowledgeDocumentEntity document where document.knowledgeBase.id = :knowledgeBaseId")
  void deleteByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

  void deleteAll();

  @Query("""
      select document from KnowledgeDocumentEntity document
      join fetch document.knowledgeBase
      where document.documentId = :documentId
      """)
  Optional<KnowledgeDocumentEntity> findByDocumentIdWithKnowledgeBase(@Param("documentId") UUID documentId);

  @Query("""
      select document from KnowledgeDocumentEntity document
      join document.knowledgeBase knowledgeBase
      where knowledgeBase.knowledgeBaseId in :knowledgeBaseIds
        and knowledgeBase.userAccountId = :userAccountId
        and document.status <> interview.pilot.knowledge.domain.KnowledgeDocumentStatus.DELETED
      order by document.createdAt desc
      """)
  List<KnowledgeDocumentEntity> findVisibleByKnowledgeBaseIdsAndUserAccountId(
      @Param("knowledgeBaseIds") Collection<UUID> knowledgeBaseIds,
      @Param("userAccountId") Long userAccountId);

  @Query("""
      select document from KnowledgeDocumentEntity document
      join document.knowledgeBase knowledgeBase
      where knowledgeBase.knowledgeBaseId in :knowledgeBaseIds
        and knowledgeBase.userAccountId = :userAccountId
        and document.activeIndexRevision > 0
        and document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY
      order by document.createdAt desc
      """)
  List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      @Param("knowledgeBaseIds") Collection<UUID> knowledgeBaseIds,
      @Param("userAccountId") Long userAccountId);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("""
      select document from KnowledgeDocumentEntity document
      join document.knowledgeBase knowledgeBase
      where knowledgeBase.knowledgeBaseId in :knowledgeBaseIds
        and knowledgeBase.userAccountId = :userAccountId
        and document.activeIndexRevision > 0
        and document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY
      order by document.createdAt desc
      """)
  List<KnowledgeDocumentEntity> lockReadyByKnowledgeBaseIdsAndUserAccountId(
      @Param("knowledgeBaseIds") Collection<UUID> knowledgeBaseIds,
      @Param("userAccountId") Long userAccountId);

  @Query("""
      select document from KnowledgeDocumentEntity document
      where (document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY
          and document.activeIndexRevision > 1)
        or (document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.FAILED
          and document.indexRevision > document.activeIndexRevision)
      """)
  List<KnowledgeDocumentEntity> findRevisionCleanupCandidates();
}
