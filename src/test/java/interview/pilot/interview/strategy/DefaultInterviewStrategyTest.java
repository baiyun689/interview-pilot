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
  void missingEvidenceProducesTargetedFollowUp() {
    var item = item("depth", "java", "Java", List.of("并发边界", "故障处置"), 2, 5);
    InterviewPlan plan = execution(item);
    AnswerEvaluation evaluation = evaluation(70, List.of(
        assessed("并发边界", true, "锁粒度")));
    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.FOLLOW_UP);
    assertThat(outcome.decision().targetCompetency()).isEqualTo("Java");
    assertThat(outcome.nextDirective().probeFocus()).contains("故障处置");
    assertThat(outcome.nextDirective().reason()).isEqualTo("EVIDENCE_GAP_FOLLOW_UP");
  }

  @Test
  void outcomeCarriesProgressWithTheCurrentTurnApplied() {
    var item = item("depth", "java", "Java", List.of("并发边界", "故障处置"), 2, 5);
    InterviewPlan plan = execution(item);
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(70, List.of(assessed("并发边界", true, "锁粒度"))), item, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 5));

    assertThat(outcome.progress()).isNotNull();
    CompetencyProgress progress = outcome.progress().progressOf("java");
    assertThat(progress.followUpCount()).isEqualTo(1);
    assertThat(progress.observedEvidence())
        .extracting(ObservedEvidence::evidenceId)
        .containsExactly("并发边界");
    assertThat(progress.missingEvidence()).containsExactly("故障处置");
  }

  @Test
  void sufficientCompetencySwitchesToTheNextOneInTheSameStage() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    AnswerEvaluation evaluation = evaluation(80, List.of(
        assessed("并发边界", true, "锁粒度")));
    TurnAssessment assessment = TurnAssessment.of(evaluation, first, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 6));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(outcome.decision().targetCompetency()).isEqualTo("MySQL");
    assertThat(outcome.nextDirective().probeFocus()).contains("索引依据");
    assertThat(outcome.nextDirective().reason()).isEqualTo("SWITCH_AFTER_EVIDENCE_SETTLED");
  }

  @Test
  void stageCompletesBeforeMovingToTheNextStage() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    var third = item("arch", "design", "系统设计", List.of("取舍分析"), 2);
    InterviewPlan plan = execution(first, second, third);
    // 历史：MySQL 已充分；本轮 Java 补足证据
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(second, evaluation(80, List.of(assessed("索引依据", true, "索引依据"))), 1)));
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(80, List.of(assessed("并发边界", true, "锁粒度"))), first, 2);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, progress, assessment, context(Difficulty.MEDIUM, "Java", 2, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(outcome.decision().targetCompetency()).isEqualTo("系统设计");
    assertThat(outcome.nextDirective().reason())
        .isEqualTo("STAGE_COMPLETED_REQUIRED_EVIDENCE");
  }

  @Test
  void unfinishedCompetencyBlocksStageSwitch() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    var third = item("arch", "design", "系统设计", List.of("取舍分析"), 2);
    InterviewPlan plan = execution(first, second, third);
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(80, List.of(assessed("并发边界", true, "锁粒度"))), first, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 9));

    assertThat(outcome.decision().targetCompetency()).isEqualTo("MySQL");
    assertThat(outcome.nextDirective().stageId()).isEqualTo("depth");
  }

  @Test
  void modelCannotFinishWhileCurrentCompetencyStillHasEvidenceGaps() {
    var item = item("depth", "java", "Java", List.of("并发边界"), 2, 5);
    InterviewPlan plan = execution(item);
    AnswerEvaluation evaluation = evaluation(70, List.of(assessed("并发边界", false, "")),
        new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.KEEP,
            "", "", "enough", 0.9));
    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.FOLLOW_UP);
  }

  @Test
  void exhaustedCompetencyIsLeftForTheNextOpenCandidate() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 1);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    // Java 已连续追问达到上限仍未获得证据
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(first, evaluation(40, List.of()), 1)));
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(40, List.of()), first, 2);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, progress, assessment, context(Difficulty.MEDIUM, "Java", 2, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(outcome.decision().targetCompetency()).isEqualTo("MySQL");
    assertThat(outcome.nextDirective().reason()).isEqualTo("SWITCH_AFTER_EVIDENCE_SETTLED");
  }

  @Test
  void hardTurnLimitFinishesEvenWithMissingEvidence() {
    var item = item("depth", "java", "Java", List.of("并发边界"), 2, 5);
    InterviewPlan plan = execution(item);
    TurnAssessment assessment = TurnAssessment.of(evaluation(40, List.of()), item, 5);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 5, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.FINISH);
    assertThat(outcome.decision().reason()).isEqualTo("TURN_BUDGET_EXHAUSTED");
  }

  @Test
  void finishDirectiveCarriesFinishReasonAndUnfinishedEvidence() {
    var item = item("depth", "java", "Java", List.of("并发边界"), 2, 5);
    InterviewPlan plan = execution(item);
    TurnAssessment assessment = TurnAssessment.of(evaluation(40, List.of()), item, 5);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 5, 5));

    TurnDirective directive = outcome.nextDirective();
    assertThat(directive.action()).isEqualTo(TurnAction.FINISH);
    assertThat(directive.finishReason()).isEqualTo("TURN_BUDGET_EXHAUSTED");
    assertThat(directive.unfinishedEvidence()).contains("Java").contains("并发边界");
  }

  @Test
  void settledFinishDirectiveHasNoUnfinishedEvidence() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(second, evaluation(80, List.of(assessed("索引依据", true, "索引依据"))), 1)));
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(80, List.of(assessed("并发边界", true, "锁粒度"))), first, 2);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, progress, assessment, context(Difficulty.MEDIUM, "Java", 2, 6));

    assertThat(outcome.decision().reason()).isEqualTo("ALL_COMPETENCIES_SETTLED");
    assertThat(outcome.nextDirective().action()).isEqualTo(TurnAction.FINISH);
    assertThat(outcome.nextDirective().unfinishedEvidence()).isEmpty();
  }

  @Test
  void allCompetenciesSettledFinishes() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(second, evaluation(80, List.of(assessed("索引依据", true, "索引依据"))), 1)));
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(80, List.of(assessed("并发边界", true, "锁粒度"))), first, 2);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, progress, assessment, context(Difficulty.MEDIUM, "Java", 2, 5));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.FINISH);
    assertThat(outcome.decision().reason()).isEqualTo("ALL_COMPETENCIES_SETTLED");
  }

  @Test
  void aiDifficultyAdjustmentIsBoundedAndAdopted() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    AnswerEvaluation evaluation = evaluation(80,
        List.of(assessed("并发边界", true, "锁粒度")),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.INCREASE,
            "Java", "", "继续", 0.9));
    TurnAssessment assessment = TurnAssessment.of(evaluation, first, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.HARD, "Java", 1, 6));

    assertThat(outcome.decision().difficultyAdjustment()).isEqualTo(DifficultyAdjustment.KEEP);
    assertThat(outcome.nextDirective().difficulty()).isEqualTo(Difficulty.HARD);
  }

  @Test
  void finishSuggestionAdjustmentIsIgnoredWhenJavaContinues() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    AnswerEvaluation evaluation = evaluation(80,
        List.of(assessed("并发边界", true, "锁粒度")),
        new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.INCREASE,
            "", "", "enough", 0.9));
    TurnAssessment assessment = TurnAssessment.of(evaluation, first, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 6));

    assertThat(outcome.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(outcome.decision().difficultyAdjustment()).isEqualTo(DifficultyAdjustment.KEEP);
    assertThat(outcome.nextDirective().difficulty()).isEqualTo(Difficulty.MEDIUM);
  }

  @Test
  void invalidSuggestionFallsBackToDeterministicDecision() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 2);
    InterviewPlan plan = execution(first, second);
    AnswerEvaluation evaluation = evaluation(80,
        List.of(assessed("并发边界", true, "锁粒度")),
        new InterviewDecision(NextStep.NEXT_TOPIC, DifficultyAdjustment.INCREASE,
            "计划外话题", "", "outside plan", 0.2));
    TurnAssessment assessment = TurnAssessment.of(evaluation, first, 1);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, InterviewProgress.from(plan, List.of()),
        assessment, context(Difficulty.MEDIUM, "Java", 1, 9));

    assertThat(outcome.decision().targetCompetency()).isEqualTo("MySQL");
    assertThat(outcome.decision().confidence()).isZero();
    assertThat(outcome.nextDirective().difficulty()).isEqualTo(Difficulty.MEDIUM);
  }

  @Test
  void stageBudgetExhaustedSwitchesStageWithReason() {
    var first = item("depth", "java", "Java", List.of("并发边界"), 1, 2);
    var second = item("depth", "mysql", "MySQL", List.of("索引依据"), 1, 2);
    var third = item("arch", "design", "系统设计", List.of("取舍分析"), 2, 2);
    InterviewPlan plan = execution(first, second, third);
    // depth 阶段预算 4 已耗尽：Java 与 MySQL 各考 2 次仍未获得证据（全 EXHAUSTED）
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(first, evaluation(40, List.of()), 1),
        turn(first, evaluation(40, List.of()), 2),
        turn(second, evaluation(40, List.of()), 3)));
    TurnAssessment assessment = TurnAssessment.of(
        evaluation(40, List.of()), second, 4);

    StrategyOutcome outcome = strategy.nextTurn(
        plan, progress, assessment, context(Difficulty.MEDIUM, "MySQL", 4, 6));

    assertThat(outcome.decision().targetCompetency()).isEqualTo("系统设计");
    assertThat(outcome.nextDirective().reason()).isEqualTo("STAGE_BUDGET_EXHAUSTED");
  }

  private static InterviewProgress.CompletedTurn turn(
      InterviewPlanItem item, AnswerEvaluation evaluation, int turnNo) {
    return new InterviewProgress.CompletedTurn(item, evaluation, turnNo);
  }

  private static AnswerEvaluation.EvidenceAssessment assessed(
      String evidenceId, boolean observed, String claim) {
    return new AnswerEvaluation.EvidenceAssessment(evidenceId, observed, claim);
  }

  private static AnswerEvaluation evaluation(
      double score, List<AnswerEvaluation.EvidenceAssessment> assessments) {
    return evaluation(score, assessments,
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "", "继续", 0.9));
  }

  private static AnswerEvaluation evaluation(
      double score, List<AnswerEvaluation.EvidenceAssessment> assessments,
      InterviewDecision suggestion) {
    return new AnswerEvaluation(
        score, "反馈", List.of("证据"), List.of(), List.of(), suggestion,
        List.of(), List.of(), assessments);
  }

  private static DecisionContext context(
      Difficulty difficulty, String competency, int currentTurn, int totalBudget) {
    return new DecisionContext(difficulty, competency, currentTurn, totalBudget, 0.55);
  }

  private static InterviewPlan execution(InterviewPlanItem... items) {
    return InterviewPlan.execution(
        List.of(items), java.util.Arrays.stream(items).mapToInt(InterviewPlanItem::turnBudget).sum(),
        List.of());
  }

  private static InterviewPlanItem item(
      String stageId, String competencyId, String competency,
      List<String> targets, int followUpLimit) {
    return item(stageId, competencyId, competency, targets, followUpLimit, 3);
  }

  private static InterviewPlanItem item(
      String stageId, String competencyId, String competency,
      List<String> targets, int followUpLimit, int turnBudget) {
    return new InterviewPlanItem(
        stageId, competencyId, competency, PlanPriority.REQUIRED, turnBudget, targets,
        List.of(InterviewQuestionMode.PROJECT), "验证能力", false,
        List.of("深度"), followUpLimit, "");
  }
}
