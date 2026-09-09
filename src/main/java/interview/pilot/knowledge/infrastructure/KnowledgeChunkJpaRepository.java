package interview.pilot.knowledge.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkJpaRepository
    extends JpaRepository<KnowledgeChunkEntity, Long>, KnowledgeChunkSearchRepository {
  @Modifying
  @Query("""
      delete from KnowledgeChunkEntity chunk
      where chunk.documentId = :documentId and chunk.indexRevision = :indexRevision
      """)
  int deleteByDocumentIdAndIndexRevision(
      @Param("documentId") UUID documentId, @Param("indexRevision") int indexRevision);

  @Modifying
  @Query("delete from KnowledgeChunkEntity chunk where chunk.documentId = :documentId")
  int deleteByDocumentId(@Param("documentId") UUID documentId);

  long countByDocumentIdAndIndexRevision(UUID documentId, int indexRevision);
}
