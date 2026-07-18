package interview.pilot.knowledge.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import interview.pilot.knowledge.domain.KnowledgeBaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "knowledge_base")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeBaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_account_id", nullable = false)
  private Long userAccountId;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "knowledge_base_id", nullable = false, unique = true, length = 36)
  private UUID knowledgeBaseId;

  @Column(nullable = false, length = 255)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private KnowledgeBaseStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static KnowledgeBaseEntity active(Long userAccountId, String name) {
    var knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.userAccountId = Objects.requireNonNull(userAccountId, "userAccountId");
    knowledgeBase.name = requireText(name, "name");
    knowledgeBase.status = KnowledgeBaseStatus.ACTIVE;
    return knowledgeBase;
  }

  public void beginDeletion() {
    status = KnowledgeBaseStatus.DELETING;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
