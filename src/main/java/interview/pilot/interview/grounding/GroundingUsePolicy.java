package interview.pilot.interview.grounding;

import java.util.Locale;

import interview.pilot.interview.strategy.TurnDirective;

public final class GroundingUsePolicy {
  private GroundingUsePolicy() {}

  public static boolean allowsQuestionGeneration(TurnDirective directive) {
    return allows(directive, "GENERATE_SCENARIO", "QUESTION_GENERATION", "GENERATE_QUESTION");
  }

  public static boolean allowsFactVerification(TurnDirective directive) {
    return allows(directive, "VERIFY_FACT", "FACT_VERIFICATION");
  }

  private static boolean allows(TurnDirective directive, String... accepted) {
    if (directive == null || !directive.ragEnabled()) return false;
    for (String configured : directive.retrievalPolicy().allowedUses()) {
      String normalized = configured.trim().toUpperCase(Locale.ROOT);
      for (String value : accepted) if (normalized.equals(value)) return true;
    }
    return false;
  }
}
