package interview.pilot.resume.domain;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResumeProfile(
    @NotBlank @Size(max = 1_000) String summary,
    @NotNull @Size(max = 64) List<@NotBlank @Size(max = 100) String> technicalSkills,
    @NotNull @Size(max = 32) List<@NotNull @Valid ProjectEvidence> projects,
    @NotNull @Size(max = 32) List<@NotBlank @Size(max = 300) String> strengths,
    @NotNull @Size(max = 32) List<@NotBlank @Size(max = 300) String> risks) {

  public record ProjectEvidence(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 1_000) String description,
      @NotNull @Size(max = 64) List<@NotBlank @Size(max = 100) String> technologies) {}

  /** Fallback when the interviewer creates a session without a resume. */
  public static ResumeProfile empty() {
    return new ResumeProfile(
        "候选人未提供简历，请完全依据岗位要求与面试技能方向评估。",
        List.of(), List.of(), List.of(), List.of());
  }
}
