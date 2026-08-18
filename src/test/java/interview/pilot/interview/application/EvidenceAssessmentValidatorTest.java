package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;

class EvidenceAssessmentValidatorTest {
  private static final List<String> TARGETS = List.of("chunking_rationale", "retrieval_and_rerank");

  private final EvidenceAssessmentValidator validator = new EvidenceAssessmentValidator();

  @Test
  void dropsAssessmentsWithEvidenceIdOutsideTargets() {
    AnswerEvaluation evaluation = evaluation(List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分"),
        new AnswerEvaluation.EvidenceAssessment("made_up_id", true, "编造的条目")));

    AnswerEvaluation validated = validator.validate("回答文本", evaluation, TARGETS);

    assertThat(validated.evidenceAssessments())
        .extracting(AnswerEvaluation.EvidenceAssessment::evidenceId)
        .containsExactly("chunking_rationale");
  }

  @Test
  void flipsObservedToNotObservedWhenClaimCannotBeGroundedInAnswer() {
    AnswerEvaluation evaluation = evaluation(List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "使用了 Qdrant 过滤")));

    AnswerEvaluation validated = validator.validate("我们按文档结构切分片段。", evaluation, TARGETS);

    assertThat(validated.evidenceAssessments())
        .containsExactly(new AnswerEvaluation.EvidenceAssessment(
            "chunking_rationale", false, ""));
  }

  @Test
  void flipsObservedToNotObservedWhenClaimIsBlank() {
    AnswerEvaluation evaluation = evaluation(List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "  ")));

    AnswerEvaluation validated = validator.validate("回答文本", evaluation, TARGETS);

    assertThat(validated.evidenceAssessments())
        .containsExactly(new AnswerEvaluation.EvidenceAssessment(
            "chunking_rationale", false, ""));
  }

  @Test
  void mergesDuplicateEvidenceIdsKeepingObservedVersion() {
    AnswerEvaluation evaluation = evaluation(List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", false, ""),
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分")));

    AnswerEvaluation validated = validator.validate(
        "我按文档结构切分片段。", evaluation, TARGETS);

    assertThat(validated.evidenceAssessments()).containsExactly(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分"));
  }

  @Test
  void keepsValidAssessmentsIntact() {
    AnswerEvaluation evaluation = evaluation(List.of(
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "按文档结构切分"),
        new AnswerEvaluation.EvidenceAssessment("retrieval_and_rerank", false, "")));

    AnswerEvaluation validated = validator.validate(
        "我按文档结构切分片段。", evaluation, TARGETS);

    assertThat(validated.evidenceAssessments()).isEqualTo(evaluation.evidenceAssessments());
  }

  @Test
  void returnsEvaluationUnchangedWithoutAssessments() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        80, "反馈", List.of("项目证据"), List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "", "继续", 0.9));

    AnswerEvaluation validated = validator.validate("回答文本", evaluation, TARGETS);

    assertThat(validated).isSameAs(evaluation);
  }

  private AnswerEvaluation evaluation(List<AnswerEvaluation.EvidenceAssessment> assessments) {
    return new AnswerEvaluation(
        80, "反馈", List.of("项目证据"), List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "", "继续", 0.9),
        List.of(), List.of(), assessments);
  }
}
