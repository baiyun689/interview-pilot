package interview.pilot.recruitment.infrastructure;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class CampaignEntities {
  private CampaignEntities() {}
  @Entity(name = "HiringBatch") @Table(name = "hiring_batch")
  public static class Batch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "organization_id") public Long organizationId;
    @Column(name = "job_id") public Long jobId;
    @Column(name = "scheme_revision_id") public Long schemeRevisionId;
    @Column(length = 120) public String name;
    @Column(name = "round_no") public int roundNo;
    @Column(name = "request_key", length = 64) public String requestKey;
    @Column(name = "request_hash", length = 64) public String requestHash;
    @Column(name = "opens_at") public Instant opensAt;
    @Column(name = "latest_start_at") public Instant latestStartAt;
    @Column(name = "closes_at") public Instant closesAt;
    @Column(length = 64) public String timezone;
    @Column(name = "created_at") public Instant createdAt;
    @Version public long version;
  }
  @Entity(name = "HiringBatchMember") @Table(name = "hiring_batch_member")
  public static class BatchMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "batch_id") public Long batchId;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "submission_no") public int submissionNo;
    @Column(name = "job_revision") public int jobRevision;
    @Column(name = "work_id") public Long workId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "approved_snapshot", columnDefinition = "json") public String approvedSnapshot;
    @Column(name = "approved_by") public Long approvedBy;
    @Column(name = "approved_at") public Instant approvedAt;
    @Version public long version;
  }
  @Entity(name = "HiringInterviewInvitation") @Table(name = "hiring_interview_invitation")
  public static class Invitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "public_id", columnDefinition = "char(36)") public String publicId;
    @Column(name = "batch_member_id") public Long batchMemberId;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "candidate_id") public Long candidateId;
    @Column(name = "round_no") public int roundNo;
    @Column(length = 20) public String status;
    @Column(name = "issued_at") public Instant issuedAt;
    @Column(name = "planned_at") public Instant plannedAt;
    @Column(name = "schedule_revision") public int scheduleRevision;
    @Version public long version;
  }
}
