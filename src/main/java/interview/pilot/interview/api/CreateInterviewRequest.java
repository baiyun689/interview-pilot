package interview.pilot.interview.api;

import java.util.List;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateInterviewRequest(
    @Positive Long resumeId,
    @NotNull @Valid JobSource jobSource,
    @NotNull Difficulty difficulty,
    @NotNull InterviewSize interviewSize,
    @NotBlank @Size(max = 64) String providerId,
    @Size(max = 5) List<UUID> knowledgeBaseIds) {

  public CreateInterviewRequest {
    knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
  }

  public record JobSource(
      @NotNull JobSourceType type,
      @Size(max = 64) String presetId,
      @Size(max = 200) String jobTitle,
      @Size(max = 20_000) String jobDescription) {
    @AssertTrue(message = "jobSource fields do not match its type")
    public boolean isValid() {
      if (type == null) return false;
      return switch (type) {
        case PRESET -> present(presetId) && !present(jobTitle) && !present(jobDescription);
        case CUSTOM -> !present(presetId) && present(jobTitle) && present(jobDescription);
      };
    }

    private static boolean present(String value) {
      return value != null && !value.isBlank();
    }
  }
}
