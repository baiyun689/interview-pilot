package interview.pilot.recruitment.infrastructure;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class AssessmentEntities {
  private AssessmentEntities() {}
  @Entity(name = "HiringScheme") @Table(name = "hiring_scheme")
  public static class Scheme {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "organization_id") public Long organizationId;
    @Column(name = "job_id") public Long jobId;
    @Column(name = "name", length = 120) public String name;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "definition", columnDefinition = "json") public String definition;
    @Column(name = "published_revision") public int publishedRevision;
    @Version public long version;
  }
  @Entity(name = "HiringSchemeRevision") @Table(name = "hiring_scheme_revision")
  public static class SchemeRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "knowledge_scope_snapshot", columnDefinition = "json") public String knowledgeScopeSnapshot;
    @Column(name = "retired") public boolean retired;
    @Column(name = "scheme_id") public Long schemeId;
    @Column(name = "revision") public int revision;
    @Column(name = "name", length = 120) public String name;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "definition", columnDefinition = "json") public String definition;
    @Column(name = "provider_id", length = 64) public String providerId;
    @Column(name = "model_name", length = 128) public String modelName;
    @Column(name = "published_at") public Instant publishedAt;
  }
  @Entity(name = "HiringWork") @Table(name = "hiring_work")
  public static class Work {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "organization_id") public Long organizationId;
    @Column(name = "job_id") public Long jobId;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "submission_no") public Integer submissionNo;
    @Column(name = "task_id") public Long taskId;
    @Column(name = "kind", length = 40) public String kind;
    @Column(name = "status", length = 20) public String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_snapshot", columnDefinition = "json") public String inputSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_snapshot", columnDefinition = "json") public String outputSnapshot;
    @Column(name = "error", length = 255) public String error;
    @Column(name = "lease_token", length = 36) public String leaseToken;
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(name = "next_attempt_at") public Instant nextAttemptAt;
    @Column(name = "attempts") public int attempts;
    @Column(name = "created_at") public Instant createdAt;
    @Version public long version;
  }
}
