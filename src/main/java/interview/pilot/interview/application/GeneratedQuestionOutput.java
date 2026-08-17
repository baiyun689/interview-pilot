package interview.pilot.interview.application;

import java.util.List;
import java.util.Objects;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;

public record GeneratedQuestionOutput(
    String question, String targetCompetency,
    GroundingMode groundingMode, List<String> evidenceRefs) {

  public GeneratedQuestionOutput {
    Objects.requireNonNull(groundingMode, "groundingMode is required");
    Objects.requireNonNull(evidenceRefs, "evidenceRefs is required");
    if ((groundingMode == GroundingMode.SKILL_GENERAL && !evidenceRefs.isEmpty())
        || (groundingMode == GroundingMode.KNOWLEDGE_ASSISTED && evidenceRefs.isEmpty())) {
      throw new IllegalArgumentException("groundingMode and evidenceRefs are inconsistent");
    }
    new GeneratedQuestion(question, targetCompetency, groundingMode, evidenceRefs);
  }

  public GeneratedQuestion toDomain() {
    return new GeneratedQuestion(question, targetCompetency, groundingMode, evidenceRefs);
  }
}
