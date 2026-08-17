package interview.pilot.interview.domain;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GeneratedQuestion(
    @NotBlank @Size(max = 2_000) String question,
    @NotBlank @Size(max = 100) String targetCompetency,
    GroundingMode groundingMode,
    List<String> evidenceRefs) {

  public GeneratedQuestion {
    question = normalize(question, "question", 2_000);
    targetCompetency = normalize(targetCompetency, "targetCompetency", 100);
    evidenceRefs = evidenceRefs == null ? List.of() : evidenceRefs.stream()
        .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    groundingMode = evidenceRefs.isEmpty()
        ? GroundingMode.SKILL_GENERAL
        : (groundingMode == null ? GroundingMode.KNOWLEDGE_ASSISTED : groundingMode);
  }

  public GeneratedQuestion(String question, String targetCompetency) {
    this(question, targetCompetency, GroundingMode.SKILL_GENERAL, List.of());
  }

  private static String normalize(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
