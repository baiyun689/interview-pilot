package interview.pilot.interview.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record InterviewReport(
    int overallScore,
    Map<String, Integer> competencyScores,
    List<String> strengths,
    List<String> improvements,
    String summary) {

  public InterviewReport {
    if (overallScore < 0 || overallScore > 100) {
      throw new IllegalArgumentException("overallScore must be between 0 and 100");
    }
    Objects.requireNonNull(competencyScores, "competencyScores must not be null");
    if (competencyScores.isEmpty() || competencyScores.size() > 50) {
      throw new IllegalArgumentException("competencyScores must contain 1 to 50 entries");
    }
    var normalizedScores = new LinkedHashMap<String, Integer>();
    competencyScores.forEach((key, value) -> {
      String normalizedKey = requiredText(key, "competency key", 100);
      if (value == null || value < 0 || value > 100) {
        throw new IllegalArgumentException("competency score must be between 0 and 100");
      }
      if (normalizedScores.putIfAbsent(normalizedKey, value) != null) {
        throw new IllegalArgumentException("competency keys must be unique");
      }
    });
    competencyScores = Map.copyOf(normalizedScores);
    strengths = requiredItems(strengths, "strengths");
    improvements = requiredItems(improvements, "improvements");
    summary = requiredText(summary, "summary", 4_000);
  }

  private static List<String> requiredItems(List<String> values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    if (values.isEmpty() || values.size() > 30) {
      throw new IllegalArgumentException(name + " must contain 1 to 30 items");
    }
    return values.stream().map(value -> requiredText(value, name + " item", 500)).toList();
  }

  private static String requiredText(String value, String name, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(name + " must be non-blank and at most " + maxLength);
    }
    return normalized;
  }
}
