package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class HybridKnowledgeRetrieverTest {
  private final KnowledgeRanker ranker = new DefaultKnowledgeRanker();

  private ValidatedKnowledgeScope scope() {
    UUID documentId = UUID.randomUUID();
    return new ValidatedKnowledgeScope(
        UUID.randomUUID(), List.of(UUID.randomUUID()),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 1)), "embed-v1");
  }

  private RetrievalIntent intent() {
    return new RetrievalIntent(
        "事务传播", "backend", "MEDIUM", List.of(), List.of(), 5, 15, 0.0, 10_000);
  }

  private KnowledgeChunk chunk(String pointId, double score, String content) {
    return new KnowledgeChunk(
        pointId, UUID.randomUUID(), "notes.md", 1, 0, "", score, content, null);
  }

  private RetrievedKnowledge retrieved(KnowledgeChunk... chunks) {
    return new RetrievedKnowledge(
        RetrievalStatus.RETRIEVED, "q", "embed-v1", List.of(chunks), Duration.ZERO, null);
  }

  private RetrievalSource returning(RetrievedKnowledge result) {
    return (scope, intent) -> result;
  }

  @Test
  void singleSourceIsDelegatedVerbatim() {
    RetrievedKnowledge only = retrieved(chunk("p1", 0.9, "REQUIRED 传播机制的基础语义说明"));
    var hybrid = new HybridKnowledgeRetriever(List.of(returning(only)), ranker);

    assertThat(hybrid.retrieve(scope(), intent())).isSameAs(only);
  }

  @Test
  void multipleSourcesMergeAndDeduplicateByPointId() {
    RetrievalSource first = returning(retrieved(
        chunk("p1", 0.90, "REQUIRED 传播机制的基础语义说明"),
        chunk("p2", 0.80, "REQUIRES_NEW 独立事务的挂起行为")));
    RetrievalSource second = returning(retrieved(
        chunk("p2", 0.80, "duplicate point id should be dropped"),
        chunk("p3", 0.70, "NESTED 保存点嵌套事务的回滚边界")));
    var hybrid = new HybridKnowledgeRetriever(List.of(first, second), ranker);

    RetrievedKnowledge result = hybrid.retrieve(scope(), intent());

    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId)
        .containsExactly("p1", "p2", "p3");
  }

  @Test
  void allNoMatchYieldsNoMatch() {
    RetrievalSource a = (scope, intent) -> RetrievedKnowledge.noMatch("q", "embed-v1", Duration.ZERO);
    RetrievalSource b = (scope, intent) -> RetrievedKnowledge.noMatch("q", "embed-v1", Duration.ZERO);
    var hybrid = new HybridKnowledgeRetriever(List.of(a, b), ranker);

    assertThat(hybrid.retrieve(scope(), intent()).status()).isEqualTo(RetrievalStatus.NO_MATCH);
  }

  @Test
  void oneSourceThrowingIsIsolatedWhileOtherSourceWins() {
    RetrievalSource failing = (scope, intent) -> { throw new RuntimeException("source down"); };
    RetrievalSource healthy = returning(retrieved(chunk("p1", 0.85, "乐观锁版本号冲突时的重试策略说明")));
    var hybrid = new HybridKnowledgeRetriever(List.of(failing, healthy), ranker);

    RetrievedKnowledge result = hybrid.retrieve(scope(), intent());

    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId).containsExactly("p1");
  }

  @Test
  void allUnavailableYieldsUnavailable() {
    RetrievalSource a = (scope, intent) ->
        RetrievedKnowledge.unavailable("q", "embed-v1", "DOWN_A", Duration.ZERO);
    RetrievalSource b = (scope, intent) ->
        RetrievedKnowledge.unavailable("q", "embed-v1", "DOWN_B", Duration.ZERO);
    var hybrid = new HybridKnowledgeRetriever(List.of(a, b), ranker);

    RetrievedKnowledge result = hybrid.retrieve(scope(), intent());
    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.failureReason()).isEqualTo("DOWN_A");
  }

  @Test
  void noSourceConfiguredYieldsUnavailable() {
    var hybrid = new HybridKnowledgeRetriever(List.of(), ranker);

    RetrievedKnowledge result = hybrid.retrieve(scope(), intent());
    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.failureReason()).isEqualTo("NO_RETRIEVAL_SOURCE");
  }
}
