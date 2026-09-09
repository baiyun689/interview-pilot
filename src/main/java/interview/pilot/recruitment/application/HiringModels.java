package interview.pilot.recruitment.application;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

public final class HiringModels {
  private HiringModels() {}
  public enum Role { ADMIN, RECRUITER, INTERVIEWER }
  public record OrganizationInput(@NotBlank @Size(max = 120) String name) {}
  public record OrganizationView(Long id, String name, Role role) {}
  public record MemberInput(@NotBlank @Email @Size(max = 320) String email, @NotNull Role role) {}
  public record MemberUpdate(@NotNull Role role, boolean active) {}
  public record MemberView(Long userId, String displayName, String email, Role role, boolean active) {}
  public record InvitationView(Long id, String token, Instant expiresAt) {}
  public record JobInput(@NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 20000) String description, @NotBlank @Size(max = 120) String location,
      @NotBlank @Size(max = 40) String employmentType, @PositiveOrZero long version) {}
  public record VersionInput(@PositiveOrZero long version) {}
  public record JobView(Long id, Long organizationId, String organizationName, String title,
      String description, String location, String employmentType, String status,
      int publishedRevision, long version, boolean canManage) {}
  public record ApplicationInput(@NotNull @Positive Long resumeId,
      @NotNull @Positive Integer jobRevision, boolean resubmit) {}
  public record ApplicationView(Long id, Long organizationId, Long jobId, String organizationName,
      String jobTitle, Long candidateId, String candidateName, String status, int submissionNo,
      int jobRevision, Long resumeRevisionId, Instant submittedAt, long version) {}
  public record ApplicationDetail(ApplicationView application, String jobDescription,
      String resumeFilename, String resumeText, List<EventView> events) {}
  public record EventView(String action, int submissionNo, int jobRevision,
      Long resumeRevisionId, Instant createdAt) {}
  public record Page<T>(List<T> items, int page, boolean hasMore) {}
  public record AuditView(Long id, Long actorId, String action, Long resourceId, Instant createdAt) {}
}
