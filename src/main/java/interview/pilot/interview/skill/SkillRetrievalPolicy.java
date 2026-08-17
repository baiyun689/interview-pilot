package interview.pilot.interview.skill;

import java.util.List;

public record SkillRetrievalPolicy(
    boolean enabled,
    List<String> scopes,
    List<String> triggerKeywords,
    List<GroundingUse> allowedUses,
    Integer topK,
    Integer candidateCount,
    Double minimumScore,
    Integer contextCharacterBudget) {

  public SkillRetrievalPolicy {
    scopes = immutable(scopes);
    triggerKeywords = immutable(triggerKeywords);
    allowedUses = allowedUses == null ? List.of() : List.copyOf(allowedUses);
    if (topK != null && (topK < 1 || topK > 6)) throw new IllegalArgumentException("topK is invalid");
    if (candidateCount != null && (candidateCount < 1 || candidateCount > 100)) {
      throw new IllegalArgumentException("candidateCount is invalid");
    }
    if (topK != null && candidateCount != null && candidateCount < topK) {
      throw new IllegalArgumentException("candidateCount must be at least topK");
    }
    if (minimumScore != null && (!Double.isFinite(minimumScore)
        || minimumScore < 0 || minimumScore > 1)) {
      throw new IllegalArgumentException("minimumScore is invalid");
    }
    if (contextCharacterBudget != null && contextCharacterBudget < 100) {
      throw new IllegalArgumentException("contextCharacterBudget is invalid");
    }
  }

  public SkillRetrievalPolicy(
      boolean enabled, List<String> scopes, List<String> triggerKeywords,
      List<GroundingUse> allowedUses) {
    this(enabled, scopes, triggerKeywords, allowedUses, null, null, null, null);
  }

  public static SkillRetrievalPolicy disabled() {
    return new SkillRetrievalPolicy(false, List.of(), List.of(), List.of(),
        null, null, null, null);
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }
}
