package interview.pilot.interview.skill;

import java.util.List;

public record SkillStageSpec(
    String id, String purpose, int order,
    List<String> entryCriteria, List<String> exitCriteria) {
  public SkillStageSpec {
    id = required(id, "stage id", 64);
    purpose = required(purpose, "stage purpose", 500);
    if (order < 0 || order > 1_000) {
      throw new IllegalArgumentException("stage order is invalid");
    }
    entryCriteria = immutable(entryCriteria);
    exitCriteria = immutable(exitCriteria);
  }

  public SkillStageSpec(String id, String purpose) {
    this(id, purpose, 0, List.of(), List.of());
  }

  private static String required(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }
}
