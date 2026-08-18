package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.skill.InterviewQuestionMode;

class TurnAssessmentTest {

  @Test
  void extractsObservedEvidenceFromValidAssessments() {
    InterviewPlanItem item = item(List.of("chunking_rationale", "retrieval_and_rerank"));
    AnswerEvaluation evaluation = evaluation(70, List.of("项目证据"), List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分"),
        new AnswerEvaluation.EvidenceAssessment("retrieval_and_rerank", false, "")));

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 3);

    assertThat(assessment.newEvidence()).containsExactly(
        new ObservedEvidence("chunking_rationale", "按文档结构切分", 3));
    assertThat(assessment.competency()).isEqualTo("RAG 设计");
    assertThat(assessment.score()).isEqualTo(70);
    assertThat(assessment.legacyTurn()).isFalse();
  }

  @Test
  void computesMissingFromTargetsNotObserved() {
    InterviewPlanItem item = item(List.of("chunking_rationale", "retrieval_and_rerank"));
    AnswerEvaluation evaluation = evaluation(70, List.of(), List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分")));

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    assertThat(assessment.missingEvidence()).containsExactly("retrieval_and_rerank");
  }

  @Test
  void ignoresObservedAssessmentsOutsideTargets() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    AnswerEvaluation evaluation = evaluation(70, List.of(), List.of(
        new AnswerEvaluation.EvidenceAssessment("not_a_target", true, "越界条目")));

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    assertThat(assessment.newEvidence()).isEmpty();
    assertThat(assessment.missingEvidence()).containsExactly("chunking_rationale");
  }

  @Test
  void marksLegacyTurnAsCoveredWhenScoreOkAndEvidencePresent() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    AnswerEvaluation evaluation = evaluation(70, List.of("项目里做过检索"), List.of());

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 2);

    assertThat(assessment.legacyTurn()).isTrue();
    assertThat(assessment.legacyCoverage()).isTrue();
  }

  @Test
  void legacyTurnNotCoveredWhenScoreBelowThreshold() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    AnswerEvaluation evaluation = evaluation(40, List.of("项目里做过检索"), List.of());

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 2);

    assertThat(assessment.legacyTurn()).isTrue();
    assertThat(assessment.legacyCoverage()).isFalse();
  }

  @Test
  void structuredTurnNeverCountsAsLegacyCoverage() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    AnswerEvaluation evaluation = evaluation(90, List.of("项目证据"), List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", false, "")));

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 2);

    assertThat(assessment.legacyTurn()).isFalse();
    assertThat(assessment.legacyCoverage()).isFalse();
  }

  @Test
  void carriesModelSuggestionAsCandidate() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    InterviewDecision suggestion = new InterviewDecision(NextStep.FINISH,
        DifficultyAdjustment.KEEP, "", "", "done", 0.9);
    AnswerEvaluation evaluation = new AnswerEvaluation(
        90, "反馈", List.of(), List.of(), List.of(), suggestion,
        List.of(), List.of(), List.of(
            new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "依据")));

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    assertThat(assessment.suggestedDecision()).isEqualTo(suggestion);
  }

  @Test
  void carriesRedFlags() {
    InterviewPlanItem item = item(List.of("chunking_rationale"));
    AnswerEvaluation evaluation = new AnswerEvaluation(
        50, "反馈", List.of(), List.of(), List.of("only_mentions_vector_search"),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "RAG 设计", "", "继续", 0.9),
        List.of(), List.of(), List.of());

    TurnAssessment assessment = TurnAssessment.of(evaluation, item, 1);

    assertThat(assessment.redFlags()).containsExactly("only_mentions_vector_search");
  }

  private InterviewPlanItem item(List<String> targets) {
    return new InterviewPlanItem(
        "architecture", "rag_design", "RAG 设计", PlanPriority.REQUIRED, 3, targets,
        List.of(InterviewQuestionMode.PROJECT), "验证 RAG 设计能力", false,
        List.of("深度"), 2, "");
  }

  private AnswerEvaluation evaluation(
      double score, List<String> evidence, List<AnswerEvaluation.EvidenceAssessment> assessments) {
    return new AnswerEvaluation(
        score, "反馈", evidence, List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "RAG 设计", "", "继续", 0.9),
        List.of(), List.of(), assessments);
  }
}
