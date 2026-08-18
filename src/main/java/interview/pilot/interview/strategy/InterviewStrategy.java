package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;

/** 唯一流程决策模块：根据 Plan 与证据进度返回唯一的下一轮执行指令。 */
public interface InterviewStrategy {
  TurnDirective firstTurn(InterviewPlan plan, Difficulty difficulty);

  StrategyOutcome nextTurn(
      InterviewPlan plan,
      InterviewProgress progress,
      TurnAssessment assessment,
      DecisionContext context);
}
