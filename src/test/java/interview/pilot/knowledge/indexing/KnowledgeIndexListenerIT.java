package interview.pilot.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.knowledge.domain.KnowledgeBaseStatus;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.knowledge.enabled=false"
})
@Testcontainers
class KnowledgeIndexListenerIT {
  private static final String REDIS_KEY_PREFIX = "interview-pilot:processing:";

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_knowledge_listener");

  @Container
  private static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));


  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    registry.add("app.knowledge.enabled", () -> "false");
  }

  @MockitoBean
  private KnowledgeIndexer indexer;

  @MockitoBean
  private interview.pilot.knowledge.storage.KnowledgeDocumentStore knowledgeDocumentStore;

  @MockitoBean
  private interview.pilot.knowledge.retrieval.KnowledgeRetriever knowledgeRetriever;

  @MockitoBean
  private interview.pilot.knowledge.retrieval.KnowledgeScopeResolver knowledgeScopeResolver;

  @MockitoBean
  private interview.pilot.common.observability.AiMetrics aiMetrics;

  @MockitoSpyBean
  private KnowledgeIndexHandler handler;

  @Autowired
  private KnowledgeBaseRepository baseRepository;

  @Autowired
  private KnowledgeBaseJpaRepository baseJpaRepository;

  @Autowired
  private KnowledgeDocumentRepository documentRepository;

  @Autowired
  private KnowledgeDocumentJpaRepository documentJpaRepository;

  @Autowired
  private AsyncTaskRepository taskRepository;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @MockitoSpyBean
  private ProcessingClaim processingClaim;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @MockitoBean
  private RedissonClient redis;

  @BeforeEach
  void resetState() {
    taskRepository.deleteAll();
    documentJpaRepository.deleteAll();
    baseJpaRepository.deleteAll();
    var route = routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX);
    rabbitAdmin.purgeQueue(route.mainQueue(), true);
    rabbitAdmin.purgeQueue(route.deadLetterQueue(), true);
    for (int retry = 1; retry <= 3; retry++) {
      rabbitAdmin.purgeQueue(route.mainQueue() + ".retry." + retry, true);
    }
  }

  @Test
  void consumesMainQueueIndexesAndMarksReady() throws Exception {
    Work work = pendingWork();
    when(indexer.index(work.document().getDocumentId(), 1)).thenReturn(new KnowledgeIndexer.IndexResult(3, "parsed", "{}"));

    send(work.message());

    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    var document = requireDocument(work.document().getDocumentId());
    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
    assertThat(document.getChunkCount()).isEqualTo(3);
  }

  @Test
  void mysqlSuccessWinsBeforeClaimAcquisitionForDuplicateMessage() throws Exception {
    Work work = pendingWork();
    work.document().setStatus(KnowledgeDocumentStatus.PROCESSING);
    documentRepository.save(work.document());
    work.document().markReady(1, "parsed text", 3);
    documentRepository.save(work.document());
    work.task().setStatus(AsyncTaskStatus.COMPLETED);
    taskRepository.save(work.task());

    String claimKey = "knowledge-index:" + work.document().getDocumentId();
    String otherOwner = "preserved-terminal-token";
    jdbc.update("update async_task set execution_token=? where id=?", otherOwner, work.task().getId());

    send(work.message());
    await(() -> queueMessageCount(routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX).mainQueue()) == 0,
        Duration.ofSeconds(5));

    verify(indexer, never()).index(org.mockito.ArgumentMatchers.any(java.util.UUID.class), org.mockito.ArgumentMatchers.anyInt());
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getExecutionToken()).isEqualTo(otherOwner);
  }

  @Test
  void retryableFailureReleasesClaimAndRoutesToRetry() throws Exception {
    Work work = pendingWork();
    when(indexer.index(work.document().getDocumentId(), 1))
        .thenThrow(new RuntimeException("transient"));

    send(work.message());

    var route = routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX);
    Object retry = rabbitTemplate.receiveAndConvert(route.mainQueue() + ".retry.1", 10_000);
    assertThat(retry).isEqualTo(work.message());
    await(() -> taskStatus(work) == AsyncTaskStatus.PUBLISHED, Duration.ofSeconds(5));
    assertThat(requireDocument(work.document().getDocumentId()).getStatus())
        .isEqualTo(KnowledgeDocumentStatus.PROCESSING);
    await(() -> taskRepository.findById(work.task().getId()).orElseThrow().getExecutionToken() == null, Duration.ofSeconds(5));
  }

  @Test
  void staleEpochDoesNotReExecute() {
    Work work = pendingWork();
    work.task().setExecutionEpoch(999);
    taskRepository.save(work.task());

    send(work.message());

    verify(handler, org.mockito.Mockito.timeout(5_000)).inspect(work.message());
    verify(indexer, never()).index(org.mockito.ArgumentMatchers.any(java.util.UUID.class), org.mockito.ArgumentMatchers.anyInt());
    assertThat(taskStatus(work)).isEqualTo(AsyncTaskStatus.PENDING);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void takeoverFencesOldIndexMetadataAndFailure(boolean failOld) throws Exception {
    Work work = pendingWork();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(indexer.index(work.document().getDocumentId(), 1)).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        if (failOld) throw new IllegalStateException("old index failure");
        return new KnowledgeIndexer.IndexResult(1, "old metadata", "{}");
      }
      return new KnowledgeIndexer.IndexResult(3, "winner metadata", "{}");
    });
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var old = pool.submit(() -> handler.handle(work.message()));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var original = taskRepository.findById(work.task().getId()).orElseThrow();
        assertThat(handler.handle(work.message())).isEqualTo(KnowledgeIndexHandler.Outcome.BUSY);
        assertThat(handler.markDead(work.message(), original.getAttemptCount(), "wrong-token")).isFalse();
        jdbc.update("update async_task set execution_lease_until=current_timestamp(6)-interval 1 second where id=?", work.task().getId());
        assertThat(handler.handle(work.message())).isEqualTo(KnowledgeIndexHandler.Outcome.TERMINAL);
        handler.releaseForRetry(work.message(), original.getAttemptCount(), original.getExecutionToken());
        assertThat(handler.markDead(work.message(), original.getAttemptCount(), original.getExecutionToken())).isFalse();
        release.countDown();
        assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(KnowledgeIndexHandler.Outcome.STALE);
        assertThat(requireDocument(work.document().getDocumentId()).getParsedText()).isEqualTo("winner metadata");
        assertThat(requireDocument(work.document().getDocumentId()).getChunkCount()).isEqualTo(3);
      } finally { release.countDown(); }
    }
  }

  @Test
  void exhaustedRetryPublishesDeadLetterAndMarksDead() throws Exception {
    Work work = pendingWork();
    when(indexer.index(work.document().getDocumentId(), 1))
        .thenThrow(new RuntimeException("persistent"));

    rabbitTemplate.convertAndSend(
        routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX).mainExchange(),
        routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX).mainRoutingKey(),
        work.message(),
        message -> {
          message.getMessageProperties().setHeader("x-retry-count", 3);
          return message;
        });

    await(() -> taskStatus(work) == AsyncTaskStatus.DEAD, Duration.ofSeconds(10));
    assertThat(rabbitTemplate.receiveAndConvert(
        routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX).deadLetterQueue(), 5_000))
        .isEqualTo(work.message());
    assertThat(requireDocument(work.document().getDocumentId()).getStatus())
        .isEqualTo(KnowledgeDocumentStatus.FAILED);
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getLastError())
        .isEqualTo("Knowledge document index retries exhausted");
  }

  @Test
  void assignedClaimNotYetCompletedDoesNotReExecuteWhenDuplicateArrives() throws Exception {
    Work work = pendingWork();
    // Simulate another worker actively processing the same document
    String claimKey = "knowledge-index:" + work.document().getDocumentId();
    String activeToken = UUID.randomUUID().toString();
    jdbc.update("update async_task set status='PUBLISHED', execution_token=?, execution_lease_until=current_timestamp(6)+interval 1 minute where id=?", activeToken, work.task().getId());

    // Send two messages — both should fail to acquire claim
    send(work.message());
    send(work.message());

    // Occupied ownership is deferred for 30 seconds without consuming a business retry.
    var route = routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX);
    await(() -> {
      var messageCount = rabbitAdmin.getQueueInfo(route.mainQueue() + ".retry.2");
      return messageCount != null && messageCount.getMessageCount() >= 1;
    }, Duration.ofSeconds(10));

    // The active claim holder remains
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getExecutionToken()).isEqualTo(activeToken);

    // Now release the active token and verify a re-delivery can complete
    jdbc.update("update async_task set execution_lease_until=current_timestamp(6)-interval 1 second where id=?", work.task().getId());
    when(indexer.index(work.document().getDocumentId(), 1)).thenReturn(new KnowledgeIndexer.IndexResult(5, "parsed", "{}"));

    // Manually re-send
    send(work.message());

    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    assertThat(requireDocument(work.document().getDocumentId()).getStatus())
        .isEqualTo(KnowledgeDocumentStatus.READY);
  }

  @Test void newDocumentRevisionRejectsLateMetadata() {
    Work work = pendingWork();
    when(indexer.index(work.document().getDocumentId(), 1)).thenAnswer(invocation -> {
      var changed = requireDocument(work.document().getDocumentId());
      changed.beginReindex();
      documentRepository.save(changed);
      return new KnowledgeIndexer.IndexResult(5, "obsolete parsed text", "{}");
    });
    assertThat(handler.handle(work.message())).isEqualTo(KnowledgeIndexHandler.Outcome.STALE);
    var current = requireDocument(work.document().getDocumentId());
    assertThat(current.getIndexRevision()).isEqualTo(2);
    assertThat(current.getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
    assertThat(current.getParsedText()).isNotEqualTo("obsolete parsed text");
  }

  private void send(TaskMessage message) {
    var route = routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX);
    rabbitTemplate.convertAndSend(route.mainExchange(), route.mainRoutingKey(), message);
  }

  private Work pendingWork() {
    KnowledgeBaseEntity base = KnowledgeBaseEntity.active(1L, "Test KB");
    base = baseRepository.save(base);
    KnowledgeDocumentEntity document = KnowledgeDocumentEntity.pending(
        base, "test.md",
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""),
        UUID.randomUUID() + "/" + UUID.randomUUID() + "/source");
    document.beginReindex();
    document = documentRepository.save(document);
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX,
        "knowledge-document:" + document.getDocumentId(),
        "{\"documentId\":\"" + document.getDocumentId() + "\",\"indexRevision\":1}");
    task.setTaskId(UUID.randomUUID());
    task = taskRepository.saveAndFlush(task);
    return new Work(base, document, task,
        new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey()));
  }

  private static RabbitTopologyConfig.PipelineRoute routeFor(AsyncTaskType type) {
    return RabbitTopologyConfig.routeFor(type);
  }

  private AsyncTaskStatus taskStatus(Work work) {
    return taskRepository.findById(work.task().getId()).orElseThrow().getStatus();
  }

  private KnowledgeDocumentEntity requireDocument(java.util.UUID documentId) {
    return documentRepository.findByDocumentId(documentId)
        .orElseThrow(() -> new AssertionError("Document not found: " + documentId));
  }

  private long queueMessageCount(String queueName) {
    var info = rabbitAdmin.getQueueInfo(queueName);
    return info == null ? 0 : info.getMessageCount();
  }

  private void await(BooleanSupplier condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Timed out waiting for asynchronous knowledge indexing");
  }

  private record Work(
      KnowledgeBaseEntity base,
      KnowledgeDocumentEntity document,
      AsyncTaskEntity task,
      TaskMessage message) {}
}
