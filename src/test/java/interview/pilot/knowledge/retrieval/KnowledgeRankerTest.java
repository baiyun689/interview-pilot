package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class KnowledgeRankerTest {
  private final KnowledgeRanker ranker = new KnowledgeRanker();

  @Test
  void sortsByRealScoreAndKeepsUsefulAdjacentChunks() {
    UUID document = UUID.randomUUID();
    var ranked = ranker.rank(List.of(
        chunk("low", UUID.randomUUID(), 0, 0.61, "低相关内容"),
        chunk("adjacent-2", document, 2, 0.88, "事务传播的异常边界"),
        chunk("adjacent-1", document, 1, 0.93, "事务传播的基础机制")), 3, 0.7, 1_000);

    assertThat(ranked).extracting(KnowledgeChunk::pointId)
        .containsExactly("adjacent-1", "adjacent-2");
  }

  @Test
  void removesNearDuplicatesAndRespectsCharacterBudget() {
    var ranked = ranker.rank(List.of(
        chunk("best", UUID.randomUUID(), 0, 0.95, "Spring 事务传播 REQUIRED 机制"),
        chunk("duplicate", UUID.randomUUID(), 0, 0.94, "Spring事务传播 REQUIRED 机制"),
        chunk("second", UUID.randomUUID(), 0, 0.90, "x".repeat(50))), 5, 0.7, 40);

    assertThat(ranked).extracting(KnowledgeChunk::pointId).containsExactly("best");
  }

  private KnowledgeChunk chunk(
      String id, UUID documentId, int index, double score, String content) {
    return new KnowledgeChunk(id, documentId, "notes.md", 1, index, "", score, content, null);
  }
}
