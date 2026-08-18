package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.skill.InterviewQuestionMode;

class InterviewProgressTest {
  private static final InterviewPlanItem ITEM_A = item(
      "project", "rag_design", "RAG 设计", PlanPriority.REQUIRED,
      List.of("chunking_rationale", "retrieval_and_rerank"), 2);
  private static final InterviewPlanItem ITEM_B = item(
      "project", "evaluation_metrics", "评测指标", PlanPriority.REQUIRED,
      List.of("evaluation_metrics"), 1);
  private static final InterviewPlanItem ITEM_C = item(
      "architecture", "system_design", "系统设计", PlanPriority.SKILL_BASELINE,
      List.of("tradeoff_analysis"), 2);

  private static InterviewPlan plan() {
    return InterviewPlan.execution(List.of(ITEM_A, ITEM_B, ITEM_C), 9, List.of());
  }

  @Test
  void rebuildsOpenProgressForFreshPlan() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of());

    assertThat(progress.lastCompetencyId()).isEmpty();
    CompetencyProgress a = progress.progressOf("rag_design");
    assertThat(a.status()).isEqualTo(CompetencyStatus.OPEN);
    assertThat(a.followUpCount()).isZero();
    assertThat(a.missingEvidence())
        .containsExactly("chunking_rationale", "retrieval_and_rerank");
    assertThat(a.observedEvidence()).isEmpty();
  }

  @Test
  void countsTrailingFollowUpsPerCompetency() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(70, List.of(), List.of()), 1),
        turn(ITEM_A, evaluation(70, List.of(), List.of()), 2)));

    assertThat(progress.progressOf("rag_design").followUpCount()).isEqualTo(2);
    assertThat(progress.lastCompetencyId()).isEqualTo("rag_design");
  }

  @Test
  void marksCompetencySufficientWhenAllTargetsObserved() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"),
            assessed("retrieval_and_rerank", true, "召回后重排"))), 1)));

    CompetencyProgress a = progress.progressOf("rag_design");
    assertThat(a.status()).isEqualTo(CompetencyStatus.SUFFICIENT);
    assertThat(a.missingEvidence()).isEmpty();
    assertThat(a.coveredTopics())
        .containsExactly("chunking_rationale", "retrieval_and_rerank");
  }

  @Test
  void marksCompetencyExhaustedWhenFollowUpLimitReached() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(60, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"))), 1),
        turn(ITEM_A, evaluation(60, List.of(), List.of()), 2),
        turn(ITEM_A, evaluation(60, List.of(), List.of()), 3)));

    CompetencyProgress a = progress.progressOf("rag_design");
    assertThat(a.followUpCount()).isEqualTo(3);
    assertThat(a.status()).isEqualTo(CompetencyStatus.EXHAUSTED);
  }

  @Test
  void resetsFollowUpCountWhenCompetencySwitchesBack() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(70, List.of(), List.of()), 1),
        turn(ITEM_B, evaluation(70, List.of(), List.of()), 2),
        turn(ITEM_A, evaluation(70, List.of(), List.of()), 3)));

    assertThat(progress.progressOf("rag_design").followUpCount()).isEqualTo(1);
    assertThat(progress.progressOf("evaluation_metrics").followUpCount()).isEqualTo(1);
  }

  @Test
  void legacyCoveredTurnsMarkCompetencySufficient() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(75, List.of("项目里做过向量检索"), List.of()), 1)));

    CompetencyProgress a = progress.progressOf("rag_design");
    assertThat(a.legacyCoverage()).isTrue();
    assertThat(a.status()).isEqualTo(CompetencyStatus.SUFFICIENT);
  }

  @Test
  void structuredTurnsOverrideLegacyCoverage() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(75, List.of("项目里做过向量检索"), List.of()), 1),
        turn(ITEM_A, evaluation(60, List.of(), List.of(
            assessed("chunking_rationale", false, ""))), 2)));

    CompetencyProgress a = progress.progressOf("rag_design");
    assertThat(a.legacyCoverage()).isFalse();
    // 结构化评估接管后不再沿用 legacy 覆盖口径；仍有缺口且追问未超上限。
    assertThat(a.status()).isEqualTo(CompetencyStatus.OPEN);
    assertThat(a.missingEvidence()).contains("retrieval_and_rerank");
  }

  @Test
  void applyAccumulatesRedFlagsWithoutDuplicates() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of());
    TurnAssessment first = assessment(
        ITEM_A, 50, List.of("only_mentions_vector_search"), List.of(), 1);
    TurnAssessment second = assessment(
        ITEM_A, 55, List.of("only_mentions_vector_search"), List.of(), 2);

    InterviewProgress after = progress.apply(first, ITEM_A).apply(second, ITEM_A);

    assertThat(after.progressOf("rag_design").redFlags())
        .containsExactly("only_mentions_vector_search");
  }

  @Test
  void stageCompleteRequiresAllRequiredCompetenciesSettled() {
    InterviewProgress complete = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"),
            assessed("retrieval_and_rerank", true, "召回后重排"))), 1),
        turn(ITEM_B, evaluation(60, List.of(), List.of()), 2),
        turn(ITEM_B, evaluation(60, List.of(), List.of()), 3)));
    assertThat(complete.stageComplete("project", plan())).isTrue();

    InterviewProgress incomplete = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"))), 1)));
    assertThat(incomplete.stageComplete("project", plan())).isFalse();
  }

  @Test
  void stageCompleteIgnoresNonRequiredCompetencies() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"),
            assessed("retrieval_and_rerank", true, "召回后重排"))), 1),
        turn(ITEM_B, evaluation(80, List.of(), List.of(
            assessed("evaluation_metrics", true, "离线指标"))), 2)));

    assertThat(progress.stageComplete("project", plan())).isTrue();
    assertThat(progress.progressOf("system_design").status()).isEqualTo(CompetencyStatus.OPEN);
  }

  @Test
  void stageCompleteWhenRequiredCompetenciesAreAllSufficientEvenIfOthersOpen() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"),
            assessed("retrieval_and_rerank", true, "召回后重排"))), 1),
        turn(ITEM_B, evaluation(80, List.of(), List.of(
            assessed("evaluation_metrics", true, "离线指标"))), 2)));

    assertThat(progress.requiredSufficient("project", plan())).isTrue();
    assertThat(progress.stageComplete("project", plan())).isTrue();
  }

  @Test
  void stageBudgetExhaustedCompletesTheStage() {
    // project 阶段预算 3+3=6，已用轮次 5：A 三轮未充分、B 两轮未充分
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 1),
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 2),
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 3),
        turn(ITEM_B, evaluation(40, List.of(), List.of()), 4),
        turn(ITEM_B, evaluation(40, List.of(), List.of()), 5)));

    assertThat(progress.stageBudgetExhausted("project", plan())).isFalse();
    assertThat(progress.stageFullySettled("project", plan())).isTrue();
    assertThat(progress.stageComplete("project", plan())).isTrue();
  }

  @Test
  void stageCompleteWhenBudgetExhaustedEvenWithRequiredOpen() {
    InterviewPlan plan = plan();
    // A 与 B 已把 6 轮预算用满
    InterviewProgress progress = InterviewProgress.from(plan, List.of(
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 1),
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 2),
        turn(ITEM_A, evaluation(40, List.of(), List.of()), 3),
        turn(ITEM_B, evaluation(40, List.of(), List.of()), 4),
        turn(ITEM_B, evaluation(40, List.of(), List.of()), 5),
        turn(ITEM_B, evaluation(40, List.of(), List.of()), 6)));

    assertThat(progress.stageBudgetExhausted("project", plan)).isTrue();
    assertThat(progress.stageComplete("project", plan)).isTrue();
  }

  @Test
  void unfinishedSummaryListsMissingEvidence() {
    InterviewProgress progress = InterviewProgress.from(plan(), List.of(
        turn(ITEM_A, evaluation(80, List.of(), List.of(
            assessed("chunking_rationale", true, "按文档结构切分"))), 1)));

    String summary = progress.unfinishedSummary(plan());

    assertThat(summary).contains("RAG 设计").contains("retrieval_and_rerank");
  }

  private static InterviewProgress.CompletedTurn turn(
      InterviewPlanItem item, AnswerEvaluation evaluation, int turnNo) {
    return new InterviewProgress.CompletedTurn(item, evaluation, turnNo);
  }

  private static TurnAssessment assessment(
      InterviewPlanItem item, double score, List<String> redFlags,
      List<AnswerEvaluation.EvidenceAssessment> assessments, int turnNo) {
    return TurnAssessment.of(new AnswerEvaluation(
        score, "反馈", List.of(), List.of(), redFlags,
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            item.competency(), "", "继续", 0.9),
        List.of(), List.of(), assessments), item, turnNo);
  }

  private static AnswerEvaluation evaluation(
      double score, List<String> evidence, List<AnswerEvaluation.EvidenceAssessment> assessments) {
    return new AnswerEvaluation(
        score, "反馈", evidence, List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "RAG 设计", "", "继续", 0.9),
        List.of(), List.of(), assessments);
  }

  private static AnswerEvaluation.EvidenceAssessment assessed(
      String evidenceId, boolean observed, String claim) {
    return new AnswerEvaluation.EvidenceAssessment(evidenceId, observed, claim);
  }

  private static InterviewPlanItem item(
      String stageId, String competencyId, String competency, PlanPriority priority,
      List<String> targets, int followUpLimit) {
    return new InterviewPlanItem(
        stageId, competencyId, competency, priority, 3, targets,
        List.of(InterviewQuestionMode.PROJECT), "验证能力", false,
        List.of("深度"), followUpLimit, "");
  }
}
