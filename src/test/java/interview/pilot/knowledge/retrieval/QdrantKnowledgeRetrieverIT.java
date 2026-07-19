package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.knowledge.config.KnowledgeProperties;

@SpringBootTest(properties = {
    "app.knowledge.enabled=true",
    "app.knowledge.files-root=./build/tmp/knowledge-test-files"
})
@Testcontainers
class QdrantKnowledgeRetrieverIT {
  private static final UUID USER_A = UUID.randomUUID();
  private static final UUID USER_B = UUID.randomUUID();
  private static final UUID KB_A = UUID.randomUUID();
  private static final UUID DOC_A1 = UUID.randomUUID();
  private static final UUID DOC_B1 = UUID.randomUUID();

  @Container
  private static final org.testcontainers.mysql.MySQLContainer MYSQL =
      new org.testcontainers.mysql.MySQLContainer(
          DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379);

  @Container
  private static final GenericContainer<?> QDRANT =
      new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.13.4"))
          .withExposedPorts(6333, 6334)
          .withStartupTimeout(java.time.Duration.ofSeconds(30));

  @DynamicPropertySource
  static void qdrantProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("app.knowledge.qdrant.host", QDRANT::getHost);
    registry.add("app.knowledge.qdrant.port", () -> QDRANT.getMappedPort(6334));
    registry.add("app.knowledge.embedding.api-key", () -> "test-key");
  }

  @MockitoBean
  private EmbeddingModel knowledgeEmbeddingModel;

  @MockitoBean
  private interview.pilot.knowledge.indexing.KnowledgeIndexer knowledgeIndexer;

  @MockitoBean
  private interview.pilot.knowledge.storage.KnowledgeDocumentStore knowledgeDocumentStore;

  @MockitoBean
  private interview.pilot.knowledge.retrieval.KnowledgeRetriever knowledgeRetriever;

  @MockitoBean
  private interview.pilot.knowledge.retrieval.KnowledgeScopeResolver knowledgeScopeResolver;

  @Autowired
  @Qualifier("knowledgeVectorStore")
  private VectorStore vectorStore;

  @Autowired
  private KnowledgeProperties properties;

  @Autowired
  private interview.pilot.common.observability.AiMetrics metrics;

  private KnowledgeRetriever retriever;

  @BeforeEach
  void setUp() {
    retriever = new QdrantKnowledgeRetriever(vectorStore, properties, metrics);
    // Prepare sample data with pre-computed embeddings
    embedAndStore(USER_A, KB_A, DOC_A1, 1, 0, "Java Spring事务传播机制详解 PROPAGATION_REQUIRED REQUIRES_NEW");
    embedAndStore(USER_A, KB_A, DOC_A1, 1, 1, "MyBatis缓存策略一级缓存二级缓存工作原理");
    embedAndStore(USER_B, UUID.randomUUID(), DOC_B1, 1, 0, "Python装饰器实现原理闭包函数式编程");
    embedAndStore(USER_B, UUID.randomUUID(), DOC_B1, 1, 1, "JavaScript事件循环宏任务微任务Promise异步");
  }

  @Test
  void retrievesOnlyUserADocumentsWithCorrectFilter() {
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC_A1, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("Spring事务传播机制", "backend", "MEDIUM",
        List.of(), List.of(), properties.topK(), properties.similarityThreshold());

    var result = retriever.retrieve(scope, intent);

    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks()).allMatch(
        chunk -> chunk.documentId().equals(DOC_A1));
    assertThat(result.chunks()).hasSizeLessThanOrEqualTo(properties.topK());
    assertThat(result.latency()).isPositive();
  }

  @Test
  void userBCannotRetrieveUserAData() {
    var scope = new ValidatedKnowledgeScope(
        USER_B, List.of(UUID.randomUUID()),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC_B1, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("Spring事务传播机制", "backend", "MEDIUM",
        List.of(), List.of(), properties.topK(), properties.similarityThreshold());

    var result = retriever.retrieve(scope, intent);

    // User B shouldn't see User A's Spring content
    assertThat(result.chunks()).allMatch(
        chunk -> chunk.documentId().equals(DOC_B1));
  }

  @Test
  void unrelatedQueryReturnsNoMatch() {
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC_A1, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("量子力学薛定谔方程波函数坍缩", "physics", "HARD",
        List.of(), List.of(), 5, 0.99);

    var result = retriever.retrieve(scope, intent);

    assertThat(result.status()).isEqualTo(RetrievalStatus.NO_MATCH);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void returnsUnavailableWhenVectorStoreFails() {
    // Given a retriever backed by a failing store
    var failingRetriever = new QdrantKnowledgeRetriever(
        new FailingVectorStore(), properties, metrics);
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC_A1, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("test", "backend", "MEDIUM",
        List.of(), List.of(), 5, 0.5);

    var result = failingRetriever.retrieve(scope, intent);

    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.chunks()).isEmpty();
    assertThat(result.failureReason()).isNotNull();
  }

  private void embedAndStore(UUID userId, UUID kbId, UUID docId,
      int revision, int chunkIdx, String content) {
    // Use mock embedding model to create vectors for deterministic test data
    var embedding = new float[1024];
    embedding[0] = chunkIdx + 0.1f;
    embedding[1] = chunkIdx + 0.2f;
    var response = new EmbeddingResponse(
        List.of(new org.springframework.ai.embedding.Embedding(embedding, 0)));
    when(knowledgeEmbeddingModel.call(ArgumentMatchers.<EmbeddingRequest>any()))
        .thenReturn(response);
    when(knowledgeEmbeddingModel.dimensions()).thenReturn(1024);

    var doc = new Document(content, Map.of(
        "user_id", userId.toString(),
        "knowledge_base_id", kbId.toString(),
        "document_id", docId.toString(),
        "index_revision", String.valueOf(revision),
        "chunk_index", String.valueOf(chunkIdx),
        "filename", "test.md"));
    vectorStore.add(List.of(doc));
  }

  private static class FailingVectorStore implements VectorStore {
    @Override public void add(List<Document> documents) {}
    @Override public List<Document> similaritySearch(SearchRequest request) {
      throw new RuntimeException("Qdrant unavailable");
    }
    @Override public List<Document> similaritySearch(String query) {
      throw new RuntimeException("Qdrant unavailable");
    }
    @Override
    public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {}
    @Override public void delete(List<String> idList) {}
  }
}
