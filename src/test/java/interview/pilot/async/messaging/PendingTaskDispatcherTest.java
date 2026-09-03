package interview.pilot.async.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;

@SpringBootTest(properties = {
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.resume-analysis-listener.auto-startup=false"
})
@Testcontainers
class PendingTaskDispatcherTest {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot");

  @Container
  private static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired
  private AsyncTaskRepository taskRepository;

  @Autowired
  private PendingTaskDispatcher dispatcher;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private ProcessingClaim processingClaim;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @BeforeEach
  void clearTasksAndMessages() {
    taskRepository.deleteAll();
    drain(RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE);
  }

  @AfterEach
  void restoreTopology() {
    rabbitAdmin.initialize();
  }

  @Test
  void publishesPendingTaskWithOnlyItsIdentityAndExecutionEpoch() {
    assertThat(processingClaim).isNotNull();
    AsyncTaskEntity task = savePending("resume:42");

    dispatcher.dispatchPendingTasks();

    Object received = rabbitTemplate.receiveAndConvert(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE, 5_000);
    assertThat(received).isEqualTo(new TaskMessage(
        task.getTaskId(), AsyncTaskType.RESUME_ANALYSIS, "resume:42"));
    assertThat(TaskMessage.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly("taskId", "taskType", "bizKey", "executionEpoch");

    AsyncTaskEntity published = taskRepository.findById(task.getId()).orElseThrow();
    assertThat(published.getPublishAttempts()).isEqualTo(1);
    assertThat(published.getAttemptCount()).isZero();
    assertThat(published.getLastPublishedAt()).isNotNull();
    assertThat(published.getLastError()).isNull();
  }

  @Test
  void recoversAndRepublishesAStaleUnconsumedQuestionPreparationClaim() {
    AsyncTaskEntity task = savePendingQuestionPreparation("interview:" + UUID.randomUUID());
    dispatcher.dispatchPendingTasks();
    AsyncTaskEntity claimed = taskRepository.findById(task.getId()).orElseThrow();
    claimed.setLastPublishedAt(Instant.now().minusSeconds(31));
    taskRepository.saveAndFlush(claimed);

    dispatcher.dispatchPendingTasks();

    AsyncTaskEntity republished = taskRepository.findById(task.getId()).orElseThrow();
    assertThat(republished.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(republished.getPublishAttempts()).isEqualTo(2);
  }

  @Test
  void dispatchesAtMostOneHundredTasksPerScan() {
    for (int index = 0; index < 101; index++) {
      savePending("resume:batch:" + index);
    }

    dispatcher.dispatchPendingTasks();

    assertThat(taskRepository.findAll())
        .filteredOn(task -> task.getPublishAttempts() == 1)
        .hasSize(100);
    assertThat(taskRepository.findAll())
        .filteredOn(task -> task.getPublishAttempts() == 0)
        .hasSize(1);
  }

  @Test
  void nackLeavesTheTaskEligibleForRepublish() {
    AsyncTaskEntity task = savePending("resume:nack");
    rabbitAdmin.deleteExchange(RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_EXCHANGE);

    dispatcher.dispatchPendingTasks();

    AsyncTaskEntity failed = taskRepository.findById(task.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(failed.getPublishAttempts()).isZero();
    assertThat(failed.getLastPublishedAt()).isNull();
    assertThat(failed.getLastError()).isNotBlank();
  }

  @Test
  void atomicPublicationClaimLetsOnlyOneConcurrentDispatcherWin() throws Exception {
    AsyncTaskEntity task = savePendingQuestionPreparation("interview:" + UUID.randomUUID());
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant cutoff = now.minusSeconds(30);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> claimAtTheSameTime(task, now, cutoff, ready, start));
      var second = executor.submit(() -> claimAtTheSameTime(task, now, cutoff, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    AsyncTaskEntity claimed = taskRepository.findById(task.getId()).orElseThrow();
    assertThat(claimed.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(claimed.getPublishAttempts()).isEqualTo(1);
    assertThat(claimed.getLastPublishedAt()).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void recoveryReturnsOnlyUnconsumedQuestionPreparationClaimsToPending() {
    AsyncTaskEntity unconsumed = savePendingQuestionPreparation("interview:" + UUID.randomUUID());
    unconsumed.setStatus(AsyncTaskStatus.PUBLISHED);
    unconsumed.setLastPublishedAt(Instant.now().minusSeconds(31));
    taskRepository.saveAndFlush(unconsumed);

    AsyncTaskEntity consumed = savePendingQuestionPreparation("interview:" + UUID.randomUUID());
    consumed.setStatus(AsyncTaskStatus.PUBLISHED);
    consumed.setAttemptCount(1);
    consumed.setLastPublishedAt(Instant.now().minusSeconds(31));
    taskRepository.saveAndFlush(consumed);

    int recovered = new TransactionTemplate(transactionManager).execute(status ->
        taskRepository.recoverUnconsumedQuestionPreparationClaims(
            AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
            AsyncTaskStatus.PENDING,
            AsyncTaskStatus.PUBLISHED,
            Instant.now().minusSeconds(30),
            "Question preparation publication lease expired"));

    assertThat(recovered).isEqualTo(1);
    assertThat(taskRepository.findById(unconsumed.getId()).orElseThrow().getStatus())
        .isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(taskRepository.findById(consumed.getId()).orElseThrow().getStatus())
        .isEqualTo(AsyncTaskStatus.PUBLISHED);
  }

  private int claimAtTheSameTime(
      AsyncTaskEntity task,
      Instant now,
      Instant cutoff,
      CountDownLatch ready,
      CountDownLatch start) throws Exception {
    return new TransactionTemplate(transactionManager).execute(status -> {
      ready.countDown();
      try {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim start timed out");
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("claim interrupted", exception);
      }
      return taskRepository.claimForPublishing(
          task.getId(), task.getExecutionEpoch(), now, cutoff,
          AsyncTaskStatus.PENDING, AsyncTaskStatus.PUBLISHED);
    });
  }

  private AsyncTaskEntity savePending(String bizKey) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.RESUME_ANALYSIS,
        bizKey,
        "{\"large\":\"payload that must remain in MySQL\"}");
    task.setTaskId(UUID.randomUUID());
    return taskRepository.saveAndFlush(task);
  }

  private AsyncTaskEntity savePendingQuestionPreparation(String bizKey) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        bizKey,
        "{\"large\":\"payload that must remain in MySQL\"}");
    task.setTaskId(UUID.randomUUID());
    return taskRepository.saveAndFlush(task);
  }

  private void drain(String queue) {
    rabbitAdmin.purgeQueue(queue, true);
  }
}
