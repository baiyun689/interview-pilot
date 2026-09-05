package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

class RubricDeckAssemblerTest {

  private static final InterviewPhase PHASE = InterviewPhase.FUNDAMENTALS;
  private final RubricDeckAssembler assembler = new RubricDeckAssembler();

  @Test
  void forcesGeneralAndScrubsSourcesWhenSnapshotIsNotRetrieved() {
    var skeleton = skeleton();
    var snapshots = Map.of(key(), new RagContextSnapshot(
        RagStatus.NO_MATCH, "query", "v1", List.of(), null));
    var output = new RubricOutput(1, List.of(item(
        GroundingMode.KNOWLEDGE_ASSISTED, List.of("p1"),
        List.of(point("要点甲", "p1"), point("要点乙", null)))));

    PreparedQuestionDeck deck = assembler.assemble(List.of(skeleton), snapshots, output);
    var question = deck.questions().getFirst();

    assertThat(question.groundingMode()).isEqualTo(GroundingMode.GENERAL);
    assertThat(question.evidenceRefs()).isEmpty();
    assertThat(question.rubric()).noneMatch(point -> point.sourcePointId() != null);
  }

  @Test
  void keepsKnowledgeAssistedWhenEvidenceIsLegalAndPointIsTraceable() {
    var skeleton = skeleton();
    var snapshots = Map.of(key(), retrieved("p1"));
    var output = new RubricOutput(1, List.of(item(
        GroundingMode.KNOWLEDGE_ASSISTED, List.of("p1"),
        List.of(point("要点甲", "p1"), point("要点乙", null)))));

    var question = assembler
        .assemble(List.of(skeleton), snapshots, output).questions().getFirst();

    assertThat(question.groundingMode()).isEqualTo(GroundingMode.KNOWLEDGE_ASSISTED);
    assertThat(question.evidenceRefs()).containsExactly("p1");
    assertThat(question.rubric()).anyMatch(point -> "p1".equals(point.sourcePointId()));
  }

  @Test
  void scrubsIllegalPointIdsAndDowngradesToGeneral() {
    var skeleton = skeleton();
    var snapshots = Map.of(key(), retrieved("p1"));
    var output = new RubricOutput(1, List.of(item(
        GroundingMode.KNOWLEDGE_ASSISTED, List.of("ghost"),
        List.of(point("要点甲", "ghost"), point("要点乙", null)))));

    var question = assembler
        .assemble(List.of(skeleton), snapshots, output).questions().getFirst();

    assertThat(question.groundingMode()).isEqualTo(GroundingMode.GENERAL);
    assertThat(question.evidenceRefs()).isEmpty();
  }

  @Test
  void rejectsWhenRubricItemForAQuestionIsMissing() {
    var skeleton = skeleton();
    var snapshots = Map.of(key(), retrieved("p1"));
    var output = new RubricOutput(1, List.of());

    assertThatThrownBy(() -> assembler.assemble(List.of(skeleton), snapshots, output))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("missing rubric item");
  }

  @Test
  void rejectsWhenRubricHasFewerThanTwoPoints() {
    var skeleton = skeleton();
    var snapshots = Map.of(key(), retrieved("p1"));
    var output = new RubricOutput(1, List.of(item(
        GroundingMode.GENERAL, List.of(), List.of(point("唯一要点", null)))));

    assertThatThrownBy(() -> assembler.assemble(List.of(skeleton), snapshots, output))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("2 to 4");
  }

  private QuestionCardKey key() {
    return QuestionCardKey.of(PHASE, 1);
  }

  private QuestionSkeletonOutput.Skeleton skeleton() {
    return new QuestionSkeletonOutput.Skeleton(
        PHASE, 1, "MVCC", "请详细解释这个知识点的实现原理与边界条件 MVCC",
        List.of("版本链", "可见性"), "mysql.mvcc", List.of("MVCC", "ReadView"),
        "如果读已提交和可重复读表现不同，你会如何解释？");
  }

  private RagContextSnapshot retrieved(String pointId) {
    var chunk = new RagContextSnapshot.Chunk(
        pointId, UUID.randomUUID(), "reference.md", 0, 0.92, "参考内容");
    return new RagContextSnapshot(RagStatus.RETRIEVED, "query", "v1", List.of(chunk), null);
  }

  private RubricOutput.Item item(
      GroundingMode mode, List<String> refs, List<RubricOutput.RubricPointData> points) {
    return new RubricOutput.Item(PHASE, 1, mode, refs, points);
  }

  private RubricOutput.RubricPointData point(String keyPoint, String sourcePointId) {
    return new RubricOutput.RubricPointData(
        keyPoint, "能够说明该要点的判定标准与关键边界", sourcePointId);
  }
}
