package interview.pilot.recruitment.infrastructure;

import java.time.Instant;
import jakarta.persistence.*;

/** Persistence records internal to recruitment; never returned by HTTP endpoints. */
public final class HiringEntities {
  private HiringEntities() {}

  @Entity(name = "HiringOrganization")
  @Table(name = "hiring_organization")
  public static class Organization {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public boolean active = true;
    @Column(name = "name", nullable = false, length = 120)
    public String name;
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;
    @Version
    public long version;
  }

  @Entity(name = "HiringPlatformOperator")
  @Table(name = "hiring_platform_operator")
  public static class PlatformOperator {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_account_id")
    public Long userAccountId;
    @Column(nullable = false)
    public boolean active;
  }

  @Entity(name = "HiringMembership")
  @Table(name = "hiring_membership")
  public static class Membership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "user_account_id")
    public Long userAccountId;
    @Column(name = "role", length = 20)
    public String role;
    @Column(name = "active")
    public boolean active;
  }

  @Entity(name = "HiringMemberInvitation")
  @Table(name = "hiring_member_invitation")
  public static class MemberInvitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "email", length = 320)
    public String email;
    @Column(name = "role", length = 20)
    public String role;
    @Column(name = "token_hash", length = 64)
    public String tokenHash;
    @Column(name = "expires_at")
    public Instant expiresAt;
    @Column(name = "accepted_at")
    public Instant acceptedAt;
  }

  @Entity(name = "HiringJob")
  @Table(name = "hiring_job")
  public static class Job {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "title", length = 200)
    public String title;
    @Column(name = "description", columnDefinition = "longtext")
    public String description;
    @Column(name = "location", length = 120)
    public String location;
    @Column(name = "employment_type", length = 40)
    public String employmentType;
    @Column(name = "status", length = 20)
    public String status;
    @Column(name = "published_revision")
    public int publishedRevision;
    @Column(name = "created_at")
    public Instant createdAt;
    @Version
    public long version;
  }

  @Entity(name = "HiringJobRevision")
  @Table(name = "hiring_job_revision")
  public static class JobRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "job_id")
    public Long jobId;
    @Column(name = "revision")
    public int revision;
    @Column(name = "title", length = 200)
    public String title;
    @Column(name = "description", columnDefinition = "longtext")
    public String description;
    @Column(name = "location", length = 120)
    public String location;
    @Column(name = "employment_type", length = 40)
    public String employmentType;
    @Column(name = "published_at")
    public Instant publishedAt;
  }

  @Entity(name = "HiringJobAssignment")
  @Table(name = "hiring_job_assignment")
  public static class JobAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "job_id")
    public Long jobId;
    @Column(name = "user_account_id")
    public Long userAccountId;
  }

  @Entity(name = "HiringResumeRevision")
  @Table(name = "hiring_resume_revision")
  public static class ResumeRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_account_id")
    public Long userAccountId;
    @Column(name = "source_resume_id")
    public Long sourceResumeId;
    @Column(name = "filename", length = 255)
    public String filename;
    @Column(name = "content_hash", length = 64)
    public String contentHash;
    @Column(name = "parsed_text", columnDefinition = "longtext")
    public String parsedText;
    @Column(name = "created_at")
    public Instant createdAt;
  }

  @Entity(name = "HiringApplication")
  @Table(name = "hiring_application")
  public static class Application {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "job_id")
    public Long jobId;
    @Column(name = "candidate_id")
    public Long candidateId;
    @Column(name = "job_revision")
    public int jobRevision;
    @Column(name = "resume_revision_id")
    public Long resumeRevisionId;
    @Column(name = "status", length = 20)
    public String status;
    @Column(name = "submission_no")
    public int submissionNo;
    @Column(name = "submitted_at")
    public Instant submittedAt;
    @Version
    public long version;
  }

  @Entity(name = "HiringApplicationEvent")
  @Table(name = "hiring_application_event")
  public static class ApplicationEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "application_id")
    public Long applicationId;
    @Column(name = "actor_id")
    public Long actorId;
    @Column(name = "action", length = 40)
    public String action;
    @Column(name = "submission_no")
    public int submissionNo;
    @Column(name = "job_revision")
    public int jobRevision;
    @Column(name = "resume_revision_id")
    public Long resumeRevisionId;
    @Column(name = "created_at")
    public Instant createdAt;
  }

  @Entity(name = "HiringAuditEvent")
  @Table(name = "hiring_audit_event")
  public static class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "organization_id")
    public Long organizationId;
    @Column(name = "actor_id")
    public Long actorId;
    @Column(name = "action", length = 60)
    public String action;
    @Column(name = "resource_id")
    public Long resourceId;
    @Column(name = "created_at")
    public Instant createdAt;
  }

}
