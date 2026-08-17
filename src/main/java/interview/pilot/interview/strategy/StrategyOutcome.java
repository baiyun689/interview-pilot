package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.InterviewDecision;

public record StrategyOutcome(InterviewDecision decision, TurnDirective nextDirective) {
  public StrategyOutcome {
    if (decision == null) throw new IllegalArgumentException("decision is required");
    if ((decision.nextStep() == interview.pilot.interview.domain.NextStep.FINISH)
        != (nextDirective == null)) {
      throw new IllegalArgumentException("nextDirective does not match decision");
    }
  }
}
