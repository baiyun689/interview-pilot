package interview.pilot.interview.skill;

public record SkillStageSpec(String id, String purpose) {
  public SkillStageSpec {
    id = required(id, "stage id", 64);
    purpose = required(purpose, "stage purpose", 500);
  }

  private static String required(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
