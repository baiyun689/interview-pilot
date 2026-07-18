package interview.pilot.interview.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GeneratedQuestion(
    @NotBlank @Size(max = 2_000) String question,
    @NotBlank @Size(max = 100) String targetCompetency) {

  public GeneratedQuestion {
    question = normalize(question, "question", 2_000);
    targetCompetency = normalize(targetCompetency, "targetCompetency", 100);
  }

  private static String normalize(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
