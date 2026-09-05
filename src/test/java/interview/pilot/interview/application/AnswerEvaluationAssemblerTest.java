package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.EvalStatus;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.RubricPoint;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

class AnswerEvaluationAssemblerTest {
  private final AnswerEvaluationAssembler assembler = new AnswerEvaluationAssembler();
  private final Instant now = Instant.parse("2026-09-05T10:00:00Z");

  @Test
  void assistedCardKeepsOnlyLegalCitationsAndClampsScore() {
    RagContextSnapshot snapshot = retrievedSnapshot();
    AnswerEvaluationInput input = input(GroundingMode.KNOWLEDGE_ASSISTED, snapshot);
    AnswerEvaluationOutput output = new AnswerEvaluationOutput(
        1,
        120,
        List.of("undo log", " ReadView "),
        List.of(new AnswerEvaluationOutput.MissingPointData("trx_id", "没提事务ID")),
        List.of("混淆了 RC 与 RR"),
        Arrays.asList("p1", "p1", "not-in-snapshot", null, " "));

    var evaluation = assembler.assemble(output, input, now);

    assertThat(evaluation.score()).isEqualTo(100);
    assertThat(evaluation.status()).isEqualTo(EvalStatus.OK);
    assertThat(evaluation.groundingMode()).isEqualTo(GroundingMode.KNOWLEDGE_ASSISTED);
    assertThat(evaluation.citedSourceIds()).containsExactly("p1");
    assertThat(evaluation.coveredPoints()).containsExactly("undo log", "ReadView");
    assertThat(evaluation.missingPoints()).singleElement()
        .satisfies(point -> {
          assertThat(point.keyPoint()).isEqualTo("trx_id");
          assertThat(point.why()).isEqualTo("没提事务ID");
        });
    assertThat(evaluation.evaluatedAt()).isEqualTo(now);
    assertThat(evaluation.model()).isEqualTo("test-model");
  }

  @Test
  void generalCardDropsModelClaimedCitationsAndFallsBack() {
    AnswerEvaluationInput input = input(GroundingMode.GENERAL, RagContextSnapshot.notConfigured());
    AnswerEvaluationOutput output = new AnswerEvaluationOutput(
        1, 70, List.of("基本概念"), List.of(), List.of(), List.of("p1"));

    var evaluation = assembler.assemble(output, input, now);

    assertThat(evaluation.status()).isEqualTo(EvalStatus.GENERAL_FALLBACK);
    assertThat(evaluation.groundingMode()).isEqualTo(GroundingMode.GENERAL);
    assertThat(evaluation.citedSourceIds()).isEmpty();
  }

  @Test
  void missingScoreIsRejected() {
    AnswerEvaluationInput input = input(GroundingMode.GENERAL, RagContextSnapshot.notConfigured());
    AnswerEvaluationOutput output = new AnswerEvaluationOutput(
        1, null, List.of(), List.of(), List.of(), List.of());

    assertThatThrownBy(() -> assembler.assemble(output, input, now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AnswerEvaluationInput input(GroundingMode groundingMode, RagContextSnapshot snapshot) {
    return new AnswerEvaluationInput(
        InterviewPhase.FUNDAMENTALS,
        "解释 MySQL MVCC",
        "MVCC 通过 undo log 和 ReadView 实现",
        Difficulty.MEDIUM,
        List.of(new RubricPoint("undo log", "能说明版本链")),
        groundingMode,
        snapshot,
        "test-provider",
        "test-model");
  }

  private RagContextSnapshot retrievedSnapshot() {
    var p1 = new RagContextSnapshot.Chunk(
        "p1", UUID.randomUUID(), "mysql.md", 0, 0.91, "undo log 版本链");
    var p2 = new RagContextSnapshot.Chunk(
        "p2", UUID.randomUUID(), "mysql.md", 1, 0.82, "ReadView 可见性");
    return new RagContextSnapshot(
        RagStatus.RETRIEVED, "mysql mvcc", "emb-v1", List.of(p1, p2), null,
        GroundingMode.KNOWLEDGE_ASSISTED, List.of("p1", "p2"));
  }
}
