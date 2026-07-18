package interview.pilot.interview.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record InterviewPlan(List<String> competencies, int totalTurnBudget) {

  public InterviewPlan {
    Objects.requireNonNull(competencies, "competencies must not be null");
    List<String> normalized = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String competency : competencies) {
      String candidate = competency == null ? "" : competency.trim();
      if (!candidate.isEmpty() && seen.add(candidate.toLowerCase(Locale.ROOT))) {
        normalized.add(candidate);
      }
    }
    competencies = List.copyOf(normalized);
    if (competencies.isEmpty() || competencies.size() > 32) {
      throw new IllegalArgumentException("competencies must not be empty");
    }
    if (competencies.stream().anyMatch(value -> value.length() > 100)) {
      throw new IllegalArgumentException("competencies entries must not exceed 100 characters");
    }
    if (totalTurnBudget < 5 || totalTurnBudget > 15) {
      throw new IllegalArgumentException("totalTurnBudget must be between 5 and 15");
    }
  }
}
