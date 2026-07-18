package interview.pilot.async.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;

@SpringBootTest(
    properties = {
        "app.async.rabbit.dispatch-initial-delay=1h",
        "app.async.resume-analysis-listener.auto-startup=false"
    },
    classes = {interview.pilot.InterviewPilotApplication.class, RabbitRetryIT.ListenerConfig.class})
@Testcontainers
class RabbitRetryIT {
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
  private TaskRetryPolicy retryPolicy;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private RecordingTestListener listener;

  @Autowired
  private Declarables asyncTaskTopology;

  @BeforeEach
  void resetQueuesAndListener() {
    listener.reset();
    for (AsyncTaskType type : AsyncTaskType.values()) {
      var route = RabbitTopologyConfig.routeFor(type);
      rabbitAdmin.purgeQueue(route.mainQueue(), true);
      rabbitAdmin.purgeQueue(route.deadLetterQueue(), true);
      for (int retryNumber = 1; retryNumber <= 3; retryNumber++) {
        rabbitAdmin.purgeQueue(route.retryQueue(retryNumber), true);
      }
    }
  }

  @Test
  void productionQueuesUseFiveThirtyAndOneHundredTwentySecondTtls() {
    assertRetryQueueArguments(RabbitTopologyConfig.routeFor(
        AsyncTaskType.RESUME_ANALYSIS));
    assertRetryQueueArguments(RabbitTopologyConfig.routeFor(
        AsyncTaskType.INTERVIEW_EVALUATION));
  }

  @Test
  void retryCountSelectsEachRetryQueueAndThenTheDlq() {
    TaskMessage task = task("resume:policy");
    var route = RabbitTopologyConfig.routeFor(task.taskType());

    for (int completedRetryDelays = 0;
        completedRetryDelays < TaskRetryPolicy.MAX_RETRY_COUNT;
        completedRetryDelays++) {
      retryPolicy.routeFailure(task, sourceWithRetryCount(completedRetryDelays));

      Message queued = rabbitTemplate.receive(
          route.retryQueue(completedRetryDelays + 1), 5_000);
      assertThat(queued).isNotNull();
      Object retryHeader = queued.getMessageProperties().getHeader(
          RabbitTopologyConfig.RETRY_COUNT_HEADER);
      assertThat(retryHeader)
          .isEqualTo(completedRetryDelays + 1);
      assertThat(rabbitTemplate.getMessageConverter().fromMessage(queued)).isEqualTo(task);
    }

    retryPolicy.routeFailure(task, sourceWithRetryCount(3));

    assertThat(rabbitTemplate.receiveAndConvert(route.deadLetterQueue(), 5_000))
        .isEqualTo(task);
  }

  @Test
  void allRetryQueuesDeadLetterExpiredMessagesBackToTheMainQueue() throws Exception {
    TaskMessage task = task("resume:short-expiration");
    var route = RabbitTopologyConfig.routeFor(task.taskType());

    for (int retryNumber = 1; retryNumber <= 3; retryNumber++) {
      int headerValue = retryNumber;
      rabbitTemplate.convertAndSend(
          route.mainExchange(),
          route.retryRoutingKey(retryNumber),
          task,
          message -> {
            message.getMessageProperties().setExpiration("100");
            message.getMessageProperties().setHeader(
                RabbitTopologyConfig.RETRY_COUNT_HEADER, headerValue);
            return message;
          });
    }

    assertThat(listener.await(Duration.ofSeconds(10))).isTrue();
    assertThat(listener.retryCounts()).containsExactlyInAnyOrder(1, 2, 3);
  }

  private void assertRetryQueueArguments(RabbitTopologyConfig.PipelineRoute route) {
    for (int retryNumber = 1; retryNumber <= 3; retryNumber++) {
      int queueNumber = retryNumber;
      Queue queue = asyncTaskTopology.getDeclarables().stream()
          .filter(Queue.class::isInstance)
          .map(Queue.class::cast)
          .filter(candidate -> candidate.getName().equals(route.retryQueue(queueNumber)))
          .findFirst()
          .orElseThrow();
      assertThat(queue.isDurable()).isTrue();
      assertThat(queue.getArguments())
          .containsEntry("x-message-ttl", RabbitTopologyConfig.RETRY_DELAYS_MILLIS[retryNumber - 1])
          .containsEntry("x-dead-letter-exchange", route.mainExchange())
          .containsEntry("x-dead-letter-routing-key", route.mainRoutingKey());
    }
  }

  private TaskMessage task(String bizKey) {
    return new TaskMessage(UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, bizKey);
  }

  private Message sourceWithRetryCount(int retryCount) {
    var properties = new MessageProperties();
    if (retryCount > 0) {
      properties.setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, retryCount);
    }
    return new Message(new byte[0], properties);
  }

  @TestConfiguration
  static class ListenerConfig {
    @Bean
    RecordingTestListener recordingTestListener() {
      return new RecordingTestListener();
    }
  }

  static class RecordingTestListener {
    private final List<Integer> retryCounts = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(3);

    @RabbitListener(queues = RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE)
    void record(TaskMessage task, Message source) {
      Number retryCount = source.getMessageProperties()
          .getHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER);
      retryCounts.add(retryCount.intValue());
      latch.countDown();
    }

    void reset() {
      retryCounts.clear();
      latch = new CountDownLatch(3);
    }

    boolean await(Duration timeout) throws InterruptedException {
      return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    List<Integer> retryCounts() {
      return List.copyOf(retryCounts);
    }
  }
}
