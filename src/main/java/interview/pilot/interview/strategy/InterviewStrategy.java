package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;

public interface InterviewStrategy {
  TurnDirective firstTurn(InterviewPlan plan, Difficulty difficulty);

  StrategyOutcome nextTurn(
      InterviewPlan plan,
      DecisionContext context,
      AnswerEvaluation assessment);
}
