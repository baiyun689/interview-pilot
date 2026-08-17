package interview.pilot.interview.skill;

import java.util.List;

public record SkillRetrievalPolicy(
    boolean enabled,
    List<String> scopes,
    List<String> triggerKeywords,
    List<String> allowedUses) {

  public SkillRetrievalPolicy {
    scopes = immutable(scopes);
    triggerKeywords = immutable(triggerKeywords);
    allowedUses = immutable(allowedUses);
  }

  public static SkillRetrievalPolicy disabled() {
    return new SkillRetrievalPolicy(false, List.of(), List.of(), List.of());
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }
}
