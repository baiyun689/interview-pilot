package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.PlatformTransactionManager;

import interview.pilot.common.observability.AiMetrics;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.DefaultKnowledgeRanker;
import interview.pilot.knowledge.retrieval.HybridKnowledgeRetriever;
import interview.pilot.knowledge.retrieval.QdrantVectorRetrievalSource;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.recruitment.application.EnterpriseKnowledgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

class EnterpriseKnowledgeSearchTest {
  private static final UUID BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final VectorStore vectors = mock(VectorStore.class);

  @Test
  void unrelatedPythonChunksBelowConfiguredThresholdProduceNoMatch() {
    // Controlled similarity, not a claim about any live embedding model's FastAPI score.
    doReturn(List.of(chunk("python", "python-basic.md", "Python 生成器、GIL 与垃圾回收机制", 0.45)))
        .when(vectors).similaritySearch(any(SearchRequest.class));

    var result = service(KnowledgeProperties.testDefaults(5, 15, 0.72, 6000))
        .search(null, 1L, List.of(BASE_ID), "fastAPI");

    assertThat(result.status()).isEqualTo(RetrievalStatus.NO_MATCH);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void relevantChunkSurvivesWhileWeakCandidatesAreExcluded() {
    doReturn(List.of(
        chunk("python", "python-basic.md", "Python 生成器、GIL 与垃圾回收机制", 0.45),
        chunk("fastapi", "fastapi.md", "FastAPI 的依赖注入、请求校验与异步路由", 0.9)))
        .when(vectors).similaritySearch(any(SearchRequest.class));

    var result = service(KnowledgeProperties.testDefaults(5, 15, 0.72, 6000))
        .search(null, 1L, List.of(BASE_ID), "fastAPI");

    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks()).extracting(c -> c.pointId()).containsExactly("fastapi");
  }

  @Test
  void searchHonorsCustomThresholdCandidateCountTopKAndCharacterBudget() {
    doReturn(List.of(
        chunk("first", "fastapi.md", "FastAPI routing ".repeat(5), 0.95),
        chunk("second", "fastapi.md", "Dependency injection ".repeat(8), 0.92),
        chunk("third", "fastapi.md", "Request validation ".repeat(8), 0.88)))
        .when(vectors).similaritySearch(any(SearchRequest.class));

    var result = service(KnowledgeProperties.testDefaults(1, 9, 0.83, 6000))
        .search(null, 1L, List.of(BASE_ID), "fastAPI");

    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectors).similaritySearch(request.capture());
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.83);
    assertThat(request.getValue().getTopK()).isEqualTo(9);
    assertThat(result.chunks()).extracting(c -> c.pointId()).containsExactly("first");

    var limited = service(KnowledgeProperties.testDefaults(5, 9, 0.83, 100))
        .search(null, 1L, List.of(BASE_ID), "fastAPI");
    assertThat(limited.chunks()).extracting(c -> c.pointId()).containsExactly("first");
  }

  private EnterpriseKnowledgeService service(KnowledgeProperties properties) {
    var ranker = new DefaultKnowledgeRanker();
    var vectorSource = new QdrantVectorRetrievalSource(vectors, properties,
        new AiMetrics(new SimpleMeterRegistry()), ranker);
    var retriever = new HybridKnowledgeRetriever(List.of(vectorSource), ranker);
    var service = spy(new EnterpriseKnowledgeService(null, null, null, null,
        properties, retriever, mock(PlatformTransactionManager.class), new ObjectMapper(), "v3"));
    // Scope authorization is independent of this regression; retain real query construction,
    // source selection, vector request and final ranking.
    doReturn(new ValidatedKnowledgeScope(null, List.of(BASE_ID),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOCUMENT_ID, 1)), "v3", 1L))
        .when(service).scope(null, 1L, List.of(BASE_ID));
    return service;
  }

  private Document chunk(String id, String filename, String text, double score) {
    var document = mock(Document.class);
    when(document.getId()).thenReturn(id);
    when(document.getText()).thenReturn(text);
    when(document.getScore()).thenReturn(score);
    when(document.getMetadata()).thenReturn(Map.of("document_id", DOCUMENT_ID.toString(),
        "filename", filename, "index_revision", "1", "chunk_index", "0"));
    return document;
  }
}
