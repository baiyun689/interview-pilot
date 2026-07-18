package interview.pilot.async.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
  void republishesOnlyAfterTheConfiguredCutoff() {
    AsyncTaskEntity task = savePending("resume:stale");
    dispatcher.dispatchPendingTasks();
    drain(RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE);

    dispatcher.dispatchPendingTasks();
    assertThat(rabbitTemplate.receive(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE, 250)).isNull();

    AsyncTaskEntity stale = taskRepository.findById(task.getId()).orElseThrow();
    stale.setLastPublishedAt(Instant.now().minusSeconds(31));
    taskRepository.saveAndFlush(stale);

    dispatcher.dispatchPendingTasks();

    assertThat(rabbitTemplate.receiveAndConvert(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE, 5_000))
        .isInstanceOf(TaskMessage.class);
    AsyncTaskEntity republished = taskRepository.findById(task.getId()).orElseThrow();
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
    assertThat(failed.getPublishAttempts()).isZero();
    assertThat(failed.getLastPublishedAt()).isNull();
    assertThat(failed.getLastError()).isNotBlank();
  }

  private AsyncTaskEntity savePending(String bizKey) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.RESUME_ANALYSIS,
        bizKey,
        "{\"large\":\"payload that must remain in MySQL\"}");
    task.setTaskId(UUID.randomUUID());
    return taskRepository.saveAndFlush(task);
  }

  private void drain(String queue) {
    rabbitAdmin.purgeQueue(queue, true);
  }
}
