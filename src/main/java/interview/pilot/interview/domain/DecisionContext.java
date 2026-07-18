package interview.pilot.interview.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record DecisionContext(
    Difficulty currentDifficulty,
    String currentCompetency,
    List<String> coveredCompetencies,
    List<String> requiredCompetencies,
    int followUpCount,
    int currentTurn,
    int totalTurnBudget,
    double minimumConfidence) {

  public DecisionContext {
    currentDifficulty = Objects.requireNonNull(currentDifficulty, "currentDifficulty must not be null");
    currentCompetency = normalize(currentCompetency);
    coveredCompetencies = normalizedDistinct(coveredCompetencies, "coveredCompetencies");
    requiredCompetencies = normalizedDistinct(requiredCompetencies, "requiredCompetencies");
    if (followUpCount < 0) {
      throw new IllegalArgumentException("followUpCount must not be negative");
    }
    if (currentTurn < 0) {
      throw new IllegalArgumentException("currentTurn must not be negative");
    }
    if (totalTurnBudget < 0) {
      throw new IllegalArgumentException("totalTurnBudget must not be negative");
    }
    if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
      throw new IllegalArgumentException("minimumConfidence must be finite and between 0 and 1");
    }
  }

  private static List<String> normalizedDistinct(List<String> values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    List<String> normalized = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      String candidate = normalize(value);
      if (!candidate.isEmpty() && seen.add(key(candidate))) {
        normalized.add(candidate);
      }
    }
    return List.copyOf(normalized);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String key(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
