package interview.pilot.interview.grounding;

import interview.pilot.interview.skill.GroundingUse;
import interview.pilot.interview.strategy.TurnDirective;

public final class GroundingUsePolicy {
  private GroundingUsePolicy() {}

  public static boolean allowsQuestionGeneration(TurnDirective directive) {
    return allows(directive, GroundingUse.GENERATE_SCENARIO);
  }

  public static boolean allowsFactVerification(TurnDirective directive) {
    return allows(directive, GroundingUse.VERIFY_FACT);
  }

  private static boolean allows(TurnDirective directive, GroundingUse accepted) {
    if (directive == null || !directive.ragEnabled()) return false;
    return directive.retrievalPolicy().allowedUses().contains(accepted);
  }
}
