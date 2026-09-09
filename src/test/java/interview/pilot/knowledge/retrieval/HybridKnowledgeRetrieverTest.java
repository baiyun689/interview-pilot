package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
        .containsExactly("p2", "p1", "p3");
    assertThat(result.chunks().getFirst().score())
        .isCloseTo((1.0 / 61 + 1.0 / 62) * 61 / 2, within(1e-12));
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
  void rawScoresAndVectorThresholdDoNotControlFusion() {
    var a = returning(retrieved(chunk("a", 0.01, "关键词独有结果"), chunk("b", 0.001, "共同命中结果")));
    var b = returning(retrieved(chunk("c", 0.99, "向量独有结果"), chunk("b", 0.98, "共同命中结果")));
    var request = new RetrievalIntent("q", "backend", "MEDIUM", List.of(), List.of(),
        3, 12, 0.99, 1000);
    var result = new HybridKnowledgeRetriever(List.of(a, b), ranker).retrieve(scope(), request);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId).containsExactly("b", "a", "c");
  }

  @Test
  void duplicateRowsWithinOneSourceCannotMultiplyItsVote() {
    var duplicate = chunk("a", 0.9, "重复候选");
    var a = returning(retrieved(duplicate, duplicate, duplicate, chunk("b", 0.8, "两路共同候选")));
    var b = returning(retrieved(chunk("b", 0.1, "两路共同候选")));
    var result = new HybridKnowledgeRetriever(List.of(a, b), ranker).retrieve(scope(), intent());
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId).containsExactly("b", "a");
    assertThat(result.chunks().get(1).score()).isEqualTo(0.5);
  }

  @Test
  void appliesNearDuplicateRemovalBudgetAndTopKAfterFusion() {
    var a = returning(retrieved(
        chunk("long", 1, "超长".repeat(60)),
        chunk("same-1", 0.9, "事务传播机制的基本工作原理"),
        chunk("same-2", 0.8, "事务传播机制的基本工作原理！"),
        chunk("other", 0.7, "锁竞争的排队策略"),
        chunk("extra", 0.6, "索引结构与查询优化")));
    var b = returning(RetrievedKnowledge.noMatch("q", "", Duration.ZERO));
    var request = new RetrievalIntent("q", "backend", "MEDIUM", List.of(), List.of(),
        2, 12, 0.99, 100);
    var result = new HybridKnowledgeRetriever(List.of(a, b), ranker).retrieve(scope(), request);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId).containsExactly("same-1", "other");
    assertThat(result.chunks().stream().mapToInt(chunk -> chunk.content().length()).sum())
        .isLessThanOrEqualTo(100);
  }

  @Test
  void emptyHealthySourceDoesNotHideAnotherSourceFailure() {
    RetrievalSource failing = (scope, intent) -> { throw new IllegalStateException("down"); };
    var empty = returning(RetrievedKnowledge.noMatch("q", "", Duration.ZERO));
    assertThat(new HybridKnowledgeRetriever(List.of(failing, empty), ranker)
        .retrieve(scope(), intent()).status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
  }

  @Test
  void unavailableVectorStillReturnsKeywordHitBelowVectorThreshold() {
    var failed = returning(RetrievedKnowledge.unavailable("q", "v3", "DOWN", Duration.ZERO));
    var healthy = returning(retrieved(chunk("keyword", 0.1, "关键词补充证据")));
    var request = new RetrievalIntent("q", "backend", "MEDIUM", List.of(), List.of(),
        1, 12, 0.99, 100);
    var result = new HybridKnowledgeRetriever(List.of(failed, healthy), ranker).retrieve(scope(), request);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId).containsExactly("keyword");
    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
  }

  @Test
  void noSourceConfiguredYieldsUnavailable() {
    var hybrid = new HybridKnowledgeRetriever(List.of(), ranker);

    RetrievedKnowledge result = hybrid.retrieve(scope(), intent());
    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.failureReason()).isEqualTo("NO_RETRIEVAL_SOURCE");
  }
}
