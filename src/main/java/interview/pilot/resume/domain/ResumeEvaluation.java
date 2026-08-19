package interview.pilot.resume.domain;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@ScoreSumConsistent
public record ResumeEvaluation(
    @NotNull @Min(0) @Max(100) Integer overallScore,
    @NotNull @Valid ScoreDetail scoreDetail,
    @NotNull @Size(max = 12) List<@NotNull @Valid Suggestion> suggestions) {

  public record ScoreDetail(
      @NotNull @Min(0) @Max(40) Integer projectScore,
      @NotNull @Min(0) @Max(20) Integer skillMatchScore,
      @NotNull @Min(0) @Max(15) Integer contentScore,
      @NotNull @Min(0) @Max(15) Integer structureScore,
      @NotNull @Min(0) @Max(10) Integer expressionScore) {}

  public record Suggestion(
      @NotBlank @Size(max = 20) @Pattern(regexp = "内容|格式|技能|项目|其他") String category,
      @NotBlank @Pattern(regexp = "高|中|低") String priority,
      @NotBlank @Size(max = 500) String issue,
      @NotBlank @Size(max = 1_000) String recommendation) {}
}
