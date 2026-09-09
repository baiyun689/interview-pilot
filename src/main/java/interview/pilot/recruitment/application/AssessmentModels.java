package interview.pilot.recruitment.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class AssessmentModels {
  private AssessmentModels() {}
  public record Fragment(String id, String text) {}
  public enum EvidenceStatus { SUPPORTED, NEEDS_VERIFICATION }
  public record Finding(@NotBlank String requirementId, @NotNull EvidenceStatus status,
      @NotNull @Size(max = 10) List<@NotBlank String> evidenceIds,
      @NotBlank @Size(max = 1000) String explanation,
      @NotNull @Size(max = 4) List<@NotBlank @Size(max = 500) String> suggestedQuestions) {}
  public record AnalysisResult(@NotEmpty @Size(max = 50) List<@Valid Finding> findings) {}
  public record AnalysisInput(String providerId, String modelName, String promptVersion,
      List<Fragment> requirements, List<Fragment> resumeEvidence) {}
  public record AnalysisRequest(@Size(max = 64) String providerId) {}
  public record WorkView(Long id, String kind, String status, String error, int attempts,
      Instant createdAt, AnalysisInput input, AnalysisResult result) {}

  public record RubricItem(@NotBlank @Size(max = 64) String id,
      @NotBlank @Size(max = 300) String point, @NotBlank @Size(max = 1000) String acceptance) {}
  public record CommonQuestion(@NotBlank @Size(max = 64) String id,
      @NotNull InterviewPhase phase, @NotBlank @Size(max = 3000) String question,
      @NotEmpty @Size(max = 8) List<@Valid RubricItem> rubric) {}
  public record Stage(@NotNull InterviewPhase phase, @Min(1) @Max(8) int questionCount,
      @Min(0) @Max(2) int followUpLimit) {}
  public record Definition(@NotNull Difficulty difficulty, @NotNull InterviewMode mode,
      @Min(5) @Max(90) int durationMinutes,
      @NotEmpty @Size(max = 4) List<@Valid Stage> stages,
      @NotNull @Size(max = 20) List<@Valid CommonQuestion> commonQuestions,
      @NotBlank @Size(max = 64) String providerId,
      @Size(max = 5) List<@NotNull UUID> knowledgeBaseIds) {
    public Definition { knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds); }
    public Definition(Difficulty difficulty, InterviewMode mode, int durationMinutes,
        List<Stage> stages, List<CommonQuestion> commonQuestions, String providerId) {
      this(difficulty, mode, durationMinutes, stages, commonQuestions, providerId, List.of());
    }
  }
  public record SchemeInput(@NotBlank @Size(max = 120) String name,
      @NotNull @Valid Definition definition, @PositiveOrZero long version) {}
  public record SchemeView(Long id, Long jobId, String name, Definition definition,
      int publishedRevision, long version) {}
  public record SchemeRevisionView(Long id, int revision, String name,
      Definition definition, String providerId, String modelName, Instant publishedAt, boolean retired) {}
}
