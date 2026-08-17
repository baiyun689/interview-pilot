package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

class AnswerGroundingValidatorTest {
  private final AnswerGroundingValidator validator = new AnswerGroundingValidator();

  @Test
  void technicalReferencesRemainSeparateFromCandidateEvidence() {
    var evaluation = new AnswerEvaluation(
        70, "ok", List.of("候选人明确说出拒绝策略"), List.of("压测数据"), List.of(),
        decision(), List.of(new AnswerEvaluation.ReferenceFact("source-1", "默认队列可能堆积")),
        List.of(new AnswerEvaluation.ReferenceFact("source-1", "候选人声称队列永不满")));

    var validated = validator.validate(evaluation, snapshot());

    assertThat(validated.evidence()).containsExactly("候选人明确说出拒绝策略");
    assertThat(validated.referenceFacts()).extracting(AnswerEvaluation.ReferenceFact::sourceId)
        .containsExactly("source-1");
  }

  @Test
  void rejectsFactsThatCiteAStaleOrInventedSource() {
    var evaluation = new AnswerEvaluation(
        70, "ok", List.of(), List.of(), List.of(), decision(),
        List.of(new AnswerEvaluation.ReferenceFact("old-source", "事实")), List.of());

    assertThatThrownBy(() -> validator.validate(evaluation, snapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current grounding snapshot");
  }

  private InterviewDecision decision() {
    return new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP, "Java", "", "test", 0.8);
  }

  private RagContextSnapshot snapshot() {
    return new RagContextSnapshot(RagStatus.RETRIEVED, "q", "embed", List.of(
        new RagContextSnapshot.Chunk(
            "source-1", UUID.randomUUID(), "notes.md", 1, 0,
            KnowledgeRole.TECHNICAL_REFERENCE, "", null, 0.9, "参考事实")), null);
  }
}
