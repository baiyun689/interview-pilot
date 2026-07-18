package interview.pilot.interview.domain;

import java.util.List;
import java.util.Objects;

public record AnswerEvaluation(
    double score,
    String feedback,
    List<String> evidence,
    List<String> missingPoints,
    InterviewDecision suggestedDecision) {

  public AnswerEvaluation {
    if (!Double.isFinite(score) || score < 0 || score > 100) {
      throw new IllegalArgumentException("score must be finite and between 0 and 100");
    }
    feedback = normalize(feedback);
    evidence = normalizedItems(evidence, "evidence");
    missingPoints = normalizedItems(missingPoints, "missingPoints");
    suggestedDecision = Objects.requireNonNull(suggestedDecision, "suggestedDecision must not be null");
  }

  private static List<String> normalizedItems(List<String> values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
