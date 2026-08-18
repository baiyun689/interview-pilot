package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.skill.InterviewQuestionMode;

class StrategyOutcomeTest {

  @Test
  void finishDecisionRequiresFinishDirective() {
    InterviewDecision finish = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "TURN_BUDGET_EXHAUSTED", 0);

    assertThatThrownBy(() -> new StrategyOutcome(finish, askDirective()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new StrategyOutcome(finish,
        TurnDirective.finish("TURN_BUDGET_EXHAUSTED", Difficulty.MEDIUM, "未完成摘要")))
        .isNotNull();
  }

  @Test
  void askDecisionRequiresAskDirective() {
    InterviewDecision ask = new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP, "MySQL", "", "切换", 0.8);

    assertThatThrownBy(() -> new StrategyOutcome(ask,
        TurnDirective.finish("TURN_BUDGET_EXHAUSTED", Difficulty.MEDIUM, "未完成摘要")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new StrategyOutcome(ask, askDirective())).isNotNull();
  }

  @Test
  void missingDirectiveIsRejected() {
    InterviewDecision finish = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "TURN_BUDGET_EXHAUSTED", 0);

    assertThatThrownBy(() -> new StrategyOutcome(finish, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TurnDirective askDirective() {
    return new TurnDirective(
        TurnAction.ASK, "depth", "MySQL", Difficulty.MEDIUM, List.of("索引依据"),
        InterviewQuestionMode.PROJECT, false, "", "切换", "", null, List.of(), "", "");
  }
}
