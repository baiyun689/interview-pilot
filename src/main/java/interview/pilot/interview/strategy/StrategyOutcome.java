package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;

/** 决策产物：动作指令 + 本轮评估应用后的证据进度快照（可由 Plan + Progress + Assessment 重算）。 */
public record StrategyOutcome(
    InterviewDecision decision,
    TurnDirective nextDirective,
    InterviewProgress progress) {

  public StrategyOutcome {
    if (decision == null) throw new IllegalArgumentException("decision is required");
    if (nextDirective == null) throw new IllegalArgumentException("nextDirective is required");
    if (progress == null) throw new IllegalArgumentException("progress is required");
    boolean finish = decision.nextStep() == NextStep.FINISH;
    if ((nextDirective.action() == TurnAction.FINISH) != finish) {
      throw new IllegalArgumentException("nextDirective does not match decision");
    }
  }
}
