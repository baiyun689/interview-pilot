package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import interview.pilot.common.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import interview.pilot.knowledge.config.KnowledgeProperties;

class QdrantVectorRetrievalSourceTest {
  private final VectorStore vectorStore = mock(VectorStore.class);
  private final AiMetrics metrics = new AiMetrics(new SimpleMeterRegistry());

  private QdrantVectorRetrievalSource source() {
    return new QdrantVectorRetrievalSource(
        vectorStore, KnowledgeProperties.testDefaults(3, 12, 0.72, 2_000), metrics,
        new DefaultKnowledgeRanker());
  }

  @Test
  void resultsWithoutScoresAreReportedAsUnavailableInsteadOfSilentZero() {
    Document unscored = document("doc-1", null);
    when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
        .thenReturn(List.of(unscored));
    var scope = scope();
    var intent = intent();

    RetrievedKnowledge result = source().retrieve(scope, intent);

    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.failureReason()).contains("SCORE_MISSING");
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void partiallyUnscoredResultsKeepOnlyScoredChunks() {
    Document unscored = document("doc-1", null);
    Document scored = document("doc-2", 0.95);
    when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
        .thenReturn(List.of(unscored, scored));
    var scope = scope();
    var intent = intent();

    RetrievedKnowledge result = source().retrieve(scope, intent);

    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks()).extracting(KnowledgeChunk::pointId)
        .containsExactly("doc-2");
  }

  private Document document(String id, Double score) {
    Document document = mock(Document.class);
    when(document.getId()).thenReturn(id);
    when(document.getText()).thenReturn("高相关片段内容与线程池拒绝策略有关");
    when(document.getMetadata()).thenReturn(Map.of(
        "document_id", UUID.randomUUID().toString(),
        "filename", "eval.md",
        "index_revision", "1",
        "chunk_index", "0",
        "section", "并发"));
    when(document.getScore()).thenReturn(score);
    return document;
  }

  private ValidatedKnowledgeScope scope() {
    UUID documentId = UUID.randomUUID();
    return new ValidatedKnowledgeScope(
        UUID.randomUUID(), List.of(UUID.randomUUID()),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 1)), "embed-v1");
  }

  private RetrievalIntent intent() {
    return new RetrievalIntent(
        "线程池拒绝策略", "并发", "MEDIUM", List.of(), List.of(),
        3, 12, 0.72, 2_000);
  }
}
