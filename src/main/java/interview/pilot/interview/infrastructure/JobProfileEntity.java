package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "job_profile")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobProfileEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_account_id", nullable = false, updatable = false)
  private Long userAccountId;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "job_id", nullable = false, unique = true, length = 36)
  private UUID jobId;

  @Column(nullable = false)
  private String title;

  @Column(name = "description_text", nullable = false, columnDefinition = "longtext")
  private String descriptionText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "requirements_snapshot", columnDefinition = "json")
  private String requirementsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "skill_snapshot", nullable = false, columnDefinition = "json")
  private String skillSnapshot;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static JobProfileEntity create(
      Long userAccountId,
      String title, String descriptionText, String requirementsSnapshot) {
    return create(userAccountId, title, descriptionText, requirementsSnapshot, """
        {"id":"custom","name":"自定义岗位","description":"历史面试兼容快照",
         "group":"CUSTOM","defaultCompetencies":[],"persona":"","rubric":"",
         "references":[],"version":"legacy"}
        """);
  }

  public static JobProfileEntity create(
      Long userAccountId,
      String title, String descriptionText, String requirementsSnapshot, String skillSnapshot) {
    var job = new JobProfileEntity();
    job.userAccountId = Objects.requireNonNull(userAccountId, "userAccountId");
    job.jobId = UUID.randomUUID();
    job.title = title;
    job.descriptionText = descriptionText;
    job.requirementsSnapshot = requirementsSnapshot;
    job.skillSnapshot = skillSnapshot;
    return job;
  }

  @Deprecated(forRemoval = true)
  public static JobProfileEntity create(
      String title, String descriptionText, String requirementsSnapshot) {
    return create(1L, title, descriptionText, requirementsSnapshot);
  }
}
