package interview.pilot.knowledge.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable lexical copy of an embedded chunk. Rows are inserted when a document revision is
 * indexed and deleted by revision/document when the matching Qdrant points are cleaned up, so
 * their lifecycle stays symmetric with the vector store.
 */
@Entity
@Table(name = "knowledge_chunk")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeChunkEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "point_id", nullable = false, unique = true, length = 36)
  private UUID pointId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "user_id", length = 36)
  private UUID userId;

  @Column(name = "organization_id")
  private Long organizationId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "knowledge_base_id", nullable = false, length = 36)
  private UUID knowledgeBaseId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "document_id", nullable = false, length = 36)
  private UUID documentId;

  @Column(name = "index_revision", nullable = false)
  private int indexRevision;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(nullable = false, length = 64)
  private String section;

  @Column(nullable = false, length = 255)
  private String filename;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static KnowledgeChunkEntity of(
      UUID pointId, UUID userId, UUID knowledgeBaseId, UUID documentId,
      int indexRevision, int chunkIndex, String section, String filename, String content) {
    var entity = new KnowledgeChunkEntity();
    entity.pointId = pointId;
    entity.userId = userId;
    entity.knowledgeBaseId = knowledgeBaseId;
    entity.documentId = documentId;
    entity.indexRevision = indexRevision;
    entity.chunkIndex = chunkIndex;
    entity.section = section == null ? "" : section;
    entity.filename = filename == null ? "" : filename;
    entity.content = content;
    return entity;
  }
}
