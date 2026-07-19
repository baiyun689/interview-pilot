package interview.pilot.interview.api;

import interview.pilot.interview.domain.Difficulty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateInterviewRequest(
    @NotNull @Positive Long resumeId,
    @Size(max = 200) String jobTitle,
    @Size(max = 20_000) String jdText,
    @NotNull Difficulty difficulty,
    @Min(5) @Max(15) int totalTurnBudget,
    @Size(max = 64) String providerId,
    @Size(max = 64) String skillId,
    @Size(max = 5) List<UUID> knowledgeBaseIds) {

  public CreateInterviewRequest {
    if (skillId == null || skillId.isBlank()) skillId = "custom";
    knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
  }

  public CreateInterviewRequest(
      Long resumeId, String jobTitle, String jdText, Difficulty difficulty,
      int totalTurnBudget, String providerId) {
    this(resumeId, jobTitle, jdText, difficulty, totalTurnBudget, providerId, "custom");
  }

  public CreateInterviewRequest(
      Long resumeId, String jobTitle, String jdText, Difficulty difficulty,
      int totalTurnBudget, String providerId, String skillId) {
    this(resumeId, jobTitle, jdText, difficulty, totalTurnBudget, providerId, skillId, List.of());
  }

  @jakarta.validation.constraints.AssertTrue(message = "jobTitle is required for custom interviews")
  public boolean isJobTitleValid() {
    return !"custom".equals(skillId) || (jobTitle != null && !jobTitle.isBlank());
  }
}
