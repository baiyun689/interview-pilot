package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

class QuestionGroundingValidatorTest {
  private final QuestionGroundingValidator validator = new QuestionGroundingValidator();

  @Test
  void questionWithoutReferencesIsCanonicalSkillGeneralQuestion() {
    var result = validator.validate(
        new GeneratedQuestion("解释线程池", "Java", GroundingMode.KNOWLEDGE_ASSISTED, List.of()),
        snapshot());

    assertThat(result.groundingMode()).isEqualTo(GroundingMode.SKILL_GENERAL);
  }

  @Test
  void acceptsOnlyReferencesFromCurrentTurnSnapshot() {
    var result = validator.validate(
        new GeneratedQuestion("解释线程池", "Java", GroundingMode.KNOWLEDGE_ASSISTED,
            List.of("source-1")), snapshot());

    assertThat(result.evidenceRefs()).containsExactly("source-1");
    assertThatThrownBy(() -> validator.validate(
        new GeneratedQuestion("解释线程池", "Java", GroundingMode.KNOWLEDGE_ASSISTED,
            List.of("invented")), snapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current grounding snapshot");
  }

  private RagContextSnapshot snapshot() {
    return new RagContextSnapshot(RagStatus.RETRIEVED, "q", "embed", List.of(
        new RagContextSnapshot.Chunk(
            "source-1", UUID.randomUUID(), "notes.md", 2, 0,
            KnowledgeRole.TECHNICAL_REFERENCE, "线程池", 3, 0.9, "参考事实")), null);
  }
}
