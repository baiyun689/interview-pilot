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
import interview.pilot.interview.domain.NextStep;

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
}
