package interview.pilot.resume.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import interview.pilot.resume.domain.ResumeStatus;
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
import lombok.Setter;

@Entity
@Table(name = "resume")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResumeEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_account_id")
  private Long userAccountId;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "resume_id", nullable = false, unique = true, length = 36)
  private UUID resumeId;

  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "parsed_text", columnDefinition = "longtext")
  private String parsedText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "skills_snapshot", columnDefinition = "json")
  private String skillsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evaluation_snapshot", columnDefinition = "json")
  private String evaluationSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ResumeStatus status;

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

  public static ResumeEntity pending(
      Long userAccountId,
      String originalFilename,
      String contentHash,
      String parsedText) {
    var resume = new ResumeEntity();
    resume.userAccountId = userAccountId;
    resume.originalFilename = originalFilename;
    resume.contentHash = contentHash;
    resume.parsedText = parsedText;
    resume.status = ResumeStatus.PENDING;
    return resume;
  }
}
