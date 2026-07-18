package interview.pilot.interview.api;

import interview.pilot.interview.domain.Difficulty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateInterviewRequest(
    @NotNull @Positive Long resumeId,
    @Size(max = 200) String jobTitle,
    @Size(max = 20_000) String jdText,
    @NotNull Difficulty difficulty,
    @Min(5) @Max(15) int totalTurnBudget,
    @Size(max = 64) String providerId,
    @Size(max = 64) String skillId) {

  public CreateInterviewRequest {
    if (skillId == null || skillId.isBlank()) skillId = "custom";
  }

  public CreateInterviewRequest(
      Long resumeId, String jobTitle, String jdText, Difficulty difficulty,
      int totalTurnBudget, String providerId) {
    this(resumeId, jobTitle, jdText, difficulty, totalTurnBudget, providerId, "custom");
  }

  @jakarta.validation.constraints.AssertTrue(message = "jobTitle is required for custom interviews")
  public boolean isJobTitleValid() {
    return !"custom".equals(skillId) || (jobTitle != null && !jobTitle.isBlank());
  }
}
