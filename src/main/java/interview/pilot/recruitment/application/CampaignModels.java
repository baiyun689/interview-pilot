package interview.pilot.recruitment.application;

import java.time.Instant;
import java.util.List;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;

public final class CampaignModels {
  private CampaignModels() {}
  public record BatchInput(@NotBlank @Size(max = 120) String name,
      @NotNull Long schemeRevisionId, @Min(1) @Max(20) int roundNo,
      @NotEmpty @Size(max = 200) List<@NotNull Long> applicationIds,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestKey,
      @NotNull Instant opensAt, @NotNull Instant latestStartAt, @NotNull Instant closesAt,
      @NotBlank @Size(max = 64) String timezone) {}
  public record PreparationInput(String providerId, String modelName, String promptVersion,
      Definition definition, List<Fragment> requirements, List<Fragment> resumeEvidence,
      ValidatedKnowledgeScope knowledgeScope) {}
  public record PreparedQuestion(@NotBlank @Size(max = 64) String id,
      @NotNull InterviewPhase phase, boolean common,
      @NotBlank @Size(max = 3000) String question,
      @NotEmpty @Size(max = 8) List<@NotNull @Valid RubricItem> rubric,
      @NotNull @Size(max = 10) List<@NotBlank String> resumeEvidenceIds,
      @Size(max = 6) List<@NotBlank String> knowledgeEvidenceIds) {
    public PreparedQuestion { knowledgeEvidenceIds = knowledgeEvidenceIds == null ? List.of() : List.copyOf(knowledgeEvidenceIds); }
    public PreparedQuestion(String id, interview.pilot.interview.domain.InterviewPhase phase, boolean common,
        String question, List<RubricItem> rubric, List<String> resumeEvidenceIds) { this(id,phase,common,question,rubric,resumeEvidenceIds,List.of()); }
  }
  public record PreparedDeck(@NotEmpty @Size(max = 20) List<@NotNull @Valid PreparedQuestion> questions,
      List<RagContextSnapshot> knowledgeEvidence) {
    public PreparedDeck { knowledgeEvidence = knowledgeEvidence == null ? List.of() : List.copyOf(knowledgeEvidence); }
    public PreparedDeck(List<PreparedQuestion> questions) { this(questions,List.of()); }
  }
  public record ApprovalInput(@PositiveOrZero long version, @NotNull @Valid PreparedDeck deck) {}
  public record BatchView(Long id, String name, Long jobId, int roundNo, String schemeName,
      Instant opensAt, Instant latestStartAt, Instant closesAt, String timezone,
      int members, int ready, int failed, int approved, int issued, long version) {}
  public record MemberView(Long id, Long applicationId, String candidateName, String status,
      String error, PreparedDeck deck, List<Fragment> resumeEvidence, boolean approved, String invitationStatus, long version) {}
  public record BatchDetail(BatchView batch, List<MemberView> members) {}
  public record PublishResult(List<Long> issued, List<Long> skipped) {}
  public record InvitationView(String id, String organizationName, String jobTitle, int roundNo,
      String status, Instant opensAt, Instant latestStartAt, Instant closesAt, int durationMinutes,
      Instant plannedAt, String timezone, int scheduleRevision, long version) {}
  public record ScheduleInput(@NotNull Instant plannedAt, @PositiveOrZero long version) {}
}
