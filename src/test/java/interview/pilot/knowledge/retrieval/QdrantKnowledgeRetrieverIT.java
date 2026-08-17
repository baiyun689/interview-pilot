package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.knowledge.application.KnowledgeBaseService;
import interview.pilot.knowledge.application.KnowledgeDocumentUploadService;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.indexing.KnowledgeIndexHandler;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@SpringBootTest(properties = {
    "app.knowledge.enabled=true",
    "app.knowledge.files-root=./build/tmp/knowledge-test-files",
    "spring.main.allow-bean-definition-overriding=true"
})
@Testcontainers
class QdrantKnowledgeRetrieverIT {
  private static final UUID USER_A = UUID.randomUUID();
  private static final UUID USER_B = UUID.randomUUID();
  private static final UUID KB_A = UUID.randomUUID();
  private static final UUID DOC_A = UUID.randomUUID();

  @Container
  private static final org.testcontainers.mysql.MySQLContainer MYSQL =
      new org.testcontainers.mysql.MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379);

  @Container
  private static final GenericContainer<?> QDRANT =
      new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.13.4"))
          .withExposedPorts(6334)
          .withStartupTimeout(java.time.Duration.ofSeconds(30));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("app.knowledge.qdrant.host", QDRANT::getHost);
    registry.add("app.knowledge.qdrant.port", () -> QDRANT.getMappedPort(6334));
    registry.add("app.knowledge.embedding.api-key", () -> "test-key");
  }

  @TestConfiguration
  static class EmbeddingOverride {
    @Bean
    @Primary
    org.springframework.ai.embedding.EmbeddingModel knowledgeEmbeddingModel() {
      return new org.springframework.ai.embedding.AbstractEmbeddingModel() {
        @Override public org.springframework.ai.embedding.EmbeddingResponse call(
            org.springframework.ai.embedding.EmbeddingRequest request) {
          var inputs = request.getInstructions();
          var embeddings = inputs.stream()
              .map(s -> new org.springframework.ai.embedding.Embedding(stableVector(), 0))
              .toList();
          return new org.springframework.ai.embedding.EmbeddingResponse(embeddings);
        }
        @Override public float[] embed(org.springframework.ai.document.Document d) {
          return stableVector();
        }
        @Override public int dimensions() { return 1024; }
        private float[] stableVector() {
          var v = new float[1024];
          v[0] = 1.0f;
          return v;
        }
      };
    }
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private interview.pilot.common.observability.AiMetrics aiMetrics;

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private UserAccountRepository users;

  @Autowired
  private KnowledgeBaseService knowledgeBases;

  @Autowired
  private KnowledgeDocumentUploadService uploads;

  @Autowired
  private AsyncTaskRepository tasks;

  @Autowired
  private KnowledgeIndexHandler indexHandler;

  @Autowired
  private KnowledgeDocumentRepository documents;

  @Autowired
  private KnowledgeScopeResolver scopeResolver;

  @BeforeEach
  void setUp() {
    embed(USER_A, KB_A, DOC_A, 1, 0, "Java Spring事务传播机制详解 PROPAGATION_REQUIRED REQUIRES_NEW");
    embed(USER_A, KB_A, DOC_A, 1, 1, "MyBatis缓存策略一级缓存二级缓存工作原理");
    embed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 0,
        "Python装饰器实现原理闭包函数式编程");
  }

  @Test
  void filtersByUserAndKnowledgeBase() {
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(
            DOC_A, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("Spring事务", "backend", "MEDIUM",
        List.of(), List.of(), 5, 0.5);

    var retriever = new QdrantKnowledgeRetriever(vectorStore,
        new interview.pilot.knowledge.config.KnowledgeProperties(
            true, java.nio.file.Path.of("./build/tmp/kt"), 800, 100, 32,
            "knowledge_chunks_v1", 5, 0.5,
            new interview.pilot.knowledge.config.KnowledgeProperties.Qdrant("localhost", 6334),
            new interview.pilot.knowledge.config.KnowledgeProperties.Embedding(
                java.net.URI.create("http://localhost"), "k", "text-embedding-v3", 1024)),
        aiMetrics);

    var result = retriever.retrieve(scope, intent);
    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks())
        .anySatisfy(chunk -> {
          assertThat(chunk.documentId()).isEqualTo(DOC_A);
          assertThat(chunk.content()).contains("Spring事务");
          assertThat(chunk.score()).isGreaterThan(0.0);
        });
  }

  @Test
  void coveredTopicHintsDoNotRemoveRelevantFollowUpChunks() {
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC_A, 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("Spring事务失败边界", "Spring事务", "HARD",
        List.of(), List.of("Spring事务"), 5, 0.5);

    var result = new QdrantKnowledgeRetriever(vectorStore,
        new interview.pilot.knowledge.config.KnowledgeProperties(
            true, java.nio.file.Path.of("./build/tmp/kt-covered"), 800, 100, 32,
            "knowledge_chunks_v1", 5, 0.5,
            new interview.pilot.knowledge.config.KnowledgeProperties.Qdrant("localhost", 6334),
            new interview.pilot.knowledge.config.KnowledgeProperties.Embedding(
                java.net.URI.create("http://localhost"), "k", "text-embedding-v3", 1024)),
        aiMetrics).retrieve(scope, intent);

    assertThat(result.chunks()).anySatisfy(chunk ->
        assertThat(chunk.content()).contains("Spring事务"));
  }

  @Test
  void uploadedDocumentCanBeIndexedAndRetrievedForInterviewScope() {
    var account = users.save(UserAccountEntity.register(
        "rag-e2e-" + UUID.randomUUID() + "@example.com", "{noop}pw", "RAG E2E"));
    var user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    var base = knowledgeBases.create(user, "Backend interview");
    var file = new MockMultipartFile(
        "file",
        "spring-rag-notes.md",
        "text/markdown",
        """
        # Spring interview notes

        Spring transaction propagation uses REQUIRED by default.
        REQUIRES_NEW suspends the current transaction and starts an independent one.
        Use optimistic locking with a version column for concurrent writes.
        """.getBytes(StandardCharsets.UTF_8));

    var uploaded = uploads.upload(user, base.knowledgeBaseId(), file);
    var task = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX,
        "knowledge-document:" + uploaded.documentId(),
        account.getId()).orElseThrow();

    var outcome = indexHandler.handle(new TaskMessage(
        task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getExecutionEpoch()));
    var indexed = documents.findByDocumentId(uploaded.documentId()).orElseThrow();
    var scope = scopeResolver.resolveForCreation(user, List.of(base.knowledgeBaseId()));
    var result = retrieve(scope, "REQUIRES_NEW transaction propagation");

    assertThat(outcome).isEqualTo(KnowledgeIndexHandler.Outcome.TERMINAL);
    assertThat(indexed.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
    assertThat(indexed.getChunkCount()).isPositive();
    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    assertThat(result.chunks())
        .anySatisfy(chunk -> {
          assertThat(chunk.documentId()).isEqualTo(uploaded.documentId());
          assertThat(chunk.content()).contains("REQUIRES_NEW");
        });
  }

  @Test
  void returnsUnavailableWhenVectorStoreFails() {
    var failingRetriever = new QdrantKnowledgeRetriever(
        new FailingVectorStore(),
        new interview.pilot.knowledge.config.KnowledgeProperties(
            true, java.nio.file.Path.of("./build/tmp/kt2"), 800, 100, 32,
            "knowledge_chunks_v1", 5, 0.5,
            new interview.pilot.knowledge.config.KnowledgeProperties.Qdrant("localhost", 6334),
            new interview.pilot.knowledge.config.KnowledgeProperties.Embedding(
                java.net.URI.create("http://localhost"), "k", "text-embedding-v3", 1024)),
        aiMetrics);
    var scope = new ValidatedKnowledgeScope(
        USER_A, List.of(KB_A),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(UUID.randomUUID(), 1)),
        "text-embedding-v3");
    var intent = new RetrievalIntent("test", "backend", "MEDIUM",
        List.of(), List.of(), 5, 0.5);

    var result = failingRetriever.retrieve(scope, intent);
    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
  }

  private void embed(UUID userId, UUID kbId, int revision, int chunkIdx, String content) {
    embed(userId, kbId, UUID.randomUUID(), revision, chunkIdx, content);
  }

  private RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, String query) {
    var intent = new RetrievalIntent(query, "backend", "MEDIUM",
        List.of(), List.of(), 5, 0.5);
    var retriever = new QdrantKnowledgeRetriever(vectorStore,
        new interview.pilot.knowledge.config.KnowledgeProperties(
            true, java.nio.file.Path.of("./build/tmp/kt3"), 800, 100, 32,
            "knowledge_chunks_v1", 5, 0.5,
            new interview.pilot.knowledge.config.KnowledgeProperties.Qdrant("localhost", 6334),
            new interview.pilot.knowledge.config.KnowledgeProperties.Embedding(
                java.net.URI.create("http://localhost"), "k", "text-embedding-v3", 1024)),
        aiMetrics);
    return retriever.retrieve(scope, intent);
  }

  private void embed(
      UUID userId, UUID kbId, UUID documentId, int revision, int chunkIdx, String content) {
    var doc = new Document(content, Map.of(
        "user_id", userId.toString(),
        "knowledge_base_id", kbId.toString(),
        "document_id", documentId.toString(),
        "index_revision", String.valueOf(revision),
        "chunk_index", String.valueOf(chunkIdx),
        "filename", "test.md"));
    vectorStore.add(List.of(doc));
  }

  private static class FailingVectorStore implements VectorStore {
    @Override public void add(List<Document> docs) {}
    @Override public List<Document> similaritySearch(SearchRequest r) {
      throw new RuntimeException("Qdrant unavailable");
    }
    @Override public List<Document> similaritySearch(String q) {
      throw new RuntimeException("Qdrant unavailable");
    }
    @Override public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression e) {}
    @Override public void delete(List<String> ids) {}
  }
}
