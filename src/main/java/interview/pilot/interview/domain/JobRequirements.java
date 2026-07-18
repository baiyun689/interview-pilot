package interview.pilot.interview.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record JobRequirements(
    @NotNull @Size(min = 1, max = 32) List<String> competencies,
    @NotNull @Size(max = 32) List<String> preferredSkills) {

  public JobRequirements {
    competencies = normalized(competencies, "competencies", true);
    preferredSkills = normalized(preferredSkills, "preferredSkills", false);
  }

  private static List<String> normalized(List<String> source, String name, boolean required) {
    Objects.requireNonNull(source, name + " must not be null");
    List<String> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String value : source) {
      String candidate = value == null ? "" : value.trim();
      if (candidate.length() > 100) {
        throw new IllegalArgumentException(name + " entries must not exceed 100 characters");
      }
      if (!candidate.isEmpty() && seen.add(candidate.toLowerCase(Locale.ROOT))) {
        result.add(candidate);
      }
    }
    if (required && result.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    if (result.size() > 32) {
      throw new IllegalArgumentException(name + " must not exceed 32 entries");
    }
    return List.copyOf(result);
  }
}
