package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.skill.InterviewQuestionMode;

class DefaultInterviewStrategyTest {
  private final DefaultInterviewStrategy strategy = new DefaultInterviewStrategy();

  @Test
  void firstTurnComesFromTheFirstExecutablePlanItem() {
    InterviewPlan plan = new InterviewPlan(List.of("项目深挖", "MySQL"), 6);

    TurnDirective directive = strategy.firstTurn(plan, Difficulty.MEDIUM);

    assertThat(directive.competency()).isEqualTo("项目深挖");
    assertThat(directive.evidenceTargets()).isNotEmpty();
    assertThat(directive.reason()).isEqualTo("PLAN_FIRST_TURN");
  }

  @Test
  void modelCannotFinishWhileAPlannedCompetencyIsUncovered() {
    InterviewPlan plan = new InterviewPlan(List.of("Java", "MySQL"), 5);
    DecisionContext context = new DecisionContext(
        Difficulty.MEDIUM, "Java", List.of("Java"), plan.competencies(),
        0, 1, 5, 0.55);
    AnswerEvaluation assessment = new AnswerEvaluation(
        90, "strong", List.of("evidence"), List.of(),
        new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.KEEP,
            "", "", "enough", 0.9));

    StrategyOutcome outcome = strategy.nextTurn(plan, context, assessment);

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(outcome.nextDirective().competency()).isEqualTo("MySQL");
  }

  @Test
  void invalidSuggestedTopicFallsBackToThePlan() {
    InterviewPlan plan = new InterviewPlan(List.of("Java", "Spring"), 5);
    DecisionContext context = new DecisionContext(
        Difficulty.MEDIUM, "Java", List.of("Java"), plan.competencies(),
        0, 1, 5, 0.55);
    AnswerEvaluation assessment = new AnswerEvaluation(
        75, "ok", List.of("evidence"), List.of("boundary"),
        new InterviewDecision(NextStep.NEXT_TOPIC, DifficultyAdjustment.INCREASE,
            "Kotlin", "coroutines", "outside plan", 0.9));

    StrategyOutcome outcome = strategy.nextTurn(plan, context, assessment);

    assertThat(outcome.decision().targetCompetency()).isEqualTo("Spring");
    assertThat(outcome.nextDirective().difficulty()).isEqualTo(Difficulty.MEDIUM);
    assertThat(outcome.nextDirective().reason()).isEqualTo("INVALID_OR_LOW_CONFIDENCE");
  }

  @Test
  void itemBudgetAndMissingEvidenceControlTheFollowUp() {
    var first = new InterviewPlanItem(
        "depth", "java", "Java", PlanPriority.REQUIRED, 2,
        List.of("并发边界", "故障处置"),
        List.of(InterviewQuestionMode.MECHANISM, InterviewQuestionMode.FAILURE),
        "JD 必考", false, List.of("boundary", "failure"), 1);
    var second = new InterviewPlanItem(
        "depth", "mysql", "MySQL", PlanPriority.REQUIRED, 3,
        List.of("索引依据"), List.of(InterviewQuestionMode.MECHANISM),
        "JD 必考", false, List.of("index"), 2);
    InterviewPlan plan = InterviewPlan.execution(List.of(first, second), 5, List.of());
    AnswerEvaluation assessment = new AnswerEvaluation(
        65, "partial", List.of("机制"), List.of("故障处置"),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "随意焦点", "继续", 0.9));

    StrategyOutcome followUp = strategy.nextTurn(plan, new DecisionContext(
        Difficulty.MEDIUM, "Java", List.of(), plan.competencies(), 0, 1, 5, 0.55), assessment);
    StrategyOutcome moveOn = strategy.nextTurn(plan, new DecisionContext(
        Difficulty.MEDIUM, "Java", List.of("Java"), plan.competencies(), 1, 2, 5, 0.55), assessment);

    assertThat(followUp.nextDirective().probeFocus())
        .contains("故障处置")
        .contains("boundary");
    assertThat(moveOn.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(moveOn.nextDirective().competency()).isEqualTo("MySQL");
  }
}
