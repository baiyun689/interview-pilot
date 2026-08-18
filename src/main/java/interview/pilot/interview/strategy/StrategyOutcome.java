package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;

public record StrategyOutcome(InterviewDecision decision, TurnDirective nextDirective) {
  public StrategyOutcome {
    if (decision == null) throw new IllegalArgumentException("decision is required");
    if (nextDirective == null) throw new IllegalArgumentException("nextDirective is required");
    boolean finish = decision.nextStep() == NextStep.FINISH;
    if ((nextDirective.action() == TurnAction.FINISH) != finish) {
      throw new IllegalArgumentException("nextDirective does not match decision");
    }
  }
}
