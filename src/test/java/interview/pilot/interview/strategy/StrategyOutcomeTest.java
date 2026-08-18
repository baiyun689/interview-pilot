package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.skill.InterviewQuestionMode;

class StrategyOutcomeTest {

  @Test
  void finishDecisionRequiresFinishDirective() {
    InterviewDecision finish = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "TURN_BUDGET_EXHAUSTED", 0);

    assertThatThrownBy(() -> new StrategyOutcome(finish, askDirective(), progress()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new StrategyOutcome(finish,
        TurnDirective.finish("TURN_BUDGET_EXHAUSTED", Difficulty.MEDIUM, "未完成摘要"),
        progress()))
        .isNotNull();
  }

  @Test
  void askDecisionRequiresAskDirective() {
    InterviewDecision ask = new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP, "MySQL", "", "切换", 0.8);

    assertThatThrownBy(() -> new StrategyOutcome(ask,
        TurnDirective.finish("TURN_BUDGET_EXHAUSTED", Difficulty.MEDIUM, "未完成摘要"),
        progress()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new StrategyOutcome(ask, askDirective(), progress())).isNotNull();
  }

  @Test
  void missingDirectiveIsRejected() {
    InterviewDecision finish = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "TURN_BUDGET_EXHAUSTED", 0);

    assertThatThrownBy(() -> new StrategyOutcome(finish, null, progress()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingProgressIsRejected() {
    InterviewDecision ask = new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP, "MySQL", "", "切换", 0.8);

    assertThatThrownBy(() -> new StrategyOutcome(ask, askDirective(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private InterviewProgress progress() {
    return InterviewProgress.from(InterviewPlan.execution(List.of(
        new interview.pilot.interview.domain.InterviewPlanItem(
            "depth", "java", "Java", interview.pilot.interview.domain.PlanPriority.REQUIRED, 3,
            List.of("并发边界"), List.of(InterviewQuestionMode.PROJECT),
            "JD 必考", false, List.of("边界"), 1, ""),
        new interview.pilot.interview.domain.InterviewPlanItem(
            "depth", "mysql", "MySQL", interview.pilot.interview.domain.PlanPriority.REQUIRED, 3,
            List.of("索引依据"), List.of(InterviewQuestionMode.PROJECT),
            "JD 必考", false, List.of("依据"), 1, "")),
        6, List.of()), List.of());
  }

  private TurnDirective askDirective() {
    return new TurnDirective(
        TurnAction.ASK, "depth", "MySQL", Difficulty.MEDIUM, List.of("索引依据"),
        InterviewQuestionMode.PROJECT, false, "", "切换", "", null, List.of(), "", "");
  }
}
