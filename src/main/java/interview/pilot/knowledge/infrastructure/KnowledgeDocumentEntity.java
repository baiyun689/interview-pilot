package interview.pilot.knowledge.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "knowledge_document")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeDocumentEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "knowledge_base_id", nullable = false)
  private KnowledgeBaseEntity knowledgeBase;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "document_id", nullable = false, unique = true, length = 36)
  private UUID documentId;

  @Column(name = "original_filename", nullable = false, length = 255)
  private String originalFilename;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Column(name = "parsed_text", columnDefinition = "longtext")
  private String parsedText;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private KnowledgeDocumentStatus status;

  @Column(name = "index_revision", nullable = false)
  private int indexRevision;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "embedding_snapshot", columnDefinition = "json")
  private String embeddingSnapshot;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  @Column(name = "failure_reason", columnDefinition = "text")
  private String failureReason;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static KnowledgeDocumentEntity pending(
      KnowledgeBaseEntity knowledgeBase,
      String originalFilename,
      String contentHash,
      String storageKey) {
    var document = new KnowledgeDocumentEntity();
    document.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase");
    document.originalFilename = requireText(originalFilename, "originalFilename");
    document.contentHash = requireText(contentHash, "contentHash");
    document.storageKey = requireText(storageKey, "storageKey");
    document.status = KnowledgeDocumentStatus.PENDING;
    document.indexRevision = 0;
    document.chunkCount = 0;
    return document;
  }

  public int beginReindex() {
    if (status == KnowledgeDocumentStatus.DELETING) {
      throw new IllegalStateException("Deleting document cannot be reindexed");
    }
    indexRevision++;
    status = KnowledgeDocumentStatus.PROCESSING;
    failureReason = null;
    return indexRevision;
  }

  public void markReady(int expectedRevision, String parsedText, int chunkCount) {
    requireProcessingRevision(expectedRevision);
    if (chunkCount < 0) {
      throw new IllegalArgumentException("chunkCount cannot be negative");
    }
    this.parsedText = parsedText;
    this.chunkCount = chunkCount;
    status = KnowledgeDocumentStatus.READY;
  }

  public void markFailed(int expectedRevision, String failureReason) {
    requireProcessingRevision(expectedRevision);
    this.failureReason = requireText(failureReason, "failureReason");
    status = KnowledgeDocumentStatus.FAILED;
  }

  public void beginDeletion() {
    if (status != KnowledgeDocumentStatus.DELETING) {
      indexRevision++;
      status = KnowledgeDocumentStatus.DELETING;
      failureReason = null;
    }
  }

  public void markDeleted() {
    if (status != KnowledgeDocumentStatus.DELETED) {
      status = KnowledgeDocumentStatus.DELETED;
      failureReason = null;
      chunkCount = 0;
      parsedText = null;
      embeddingSnapshot = null;
    }
  }

  private void requireProcessingRevision(int expectedRevision) {
    if (indexRevision != expectedRevision) {
      throw new IllegalStateException("Stale document index revision");
    }
    if (status != KnowledgeDocumentStatus.PROCESSING) {
      throw new IllegalStateException("Document is not being indexed");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
