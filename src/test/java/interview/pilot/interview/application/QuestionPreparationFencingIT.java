package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import interview.pilot.async.domain.*;
import interview.pilot.async.infrastructure.*;
import interview.pilot.async.messaging.*;
import interview.pilot.async.application.AsyncTaskService;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.*;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL and RabbitMQ; barriers and failures replace only external generation work. */
@SpringBootTest(properties = {
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.interview.answer-recovery.initial-delay=1h",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class QuestionPreparationFencingIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("preparation_fencing");
  @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }
  @MockitoBean RedissonClient redis;
  @MockitoBean ProcessingClaim claims;
  @Autowired TaskMessagePublisher publisher;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewQuestionCardRepository cards;
  @Autowired UserAccountRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired RabbitTemplate rabbit;
  @Autowired RabbitAdmin rabbitAdmin;
  @Autowired AsyncTaskService taskService;

  @Autowired private QuestionPreparationHandler handler;
  @MockitoBean private QuestionSkeletonGenerator generator;
  @MockitoBean private QuestionRagRetriever retrieval;
  @MockitoBean private RubricGenerator rubrics;
  private PreparedQuestionDeck validDeck;
  private TaskMessage message;
  private InterviewSessionEntity session;

  @BeforeEach void setUp() {
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE, false);
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.INTERVIEW_PREPARATION_DLQ, false);
    for (int retry = 1; retry <= 3; retry++) {
      rabbitAdmin.purgeQueue(RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE + ".retry." + retry, false);
    }
    tasks.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    var user = users.saveAndFlush(UserAccountEntity.register("fencing@example.test", "hash", "Fencing"));
    var mapper = new ObjectMapper();
    var brief = new InterviewBriefSnapshot(JobSourceType.CUSTOM, null, null, "Backend", "Java backend",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD, "test", "model", null, 2);
    session = sessions.saveAndFlush(InterviewSessionEntity.preparing(user.getId(), null,
        brief.difficulty(), brief.interviewSize(), brief.jobSourceType(), brief.jobTitle(),
        brief.providerId(), brief.modelName(), mapper.writeValueAsString(brief), null));
    var task = tasks.saveAndFlush(AsyncTaskEntity.pending(user.getId(),
        AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + session.getSessionId(), "{}"));
    message = new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey());
    doThrow(new IllegalStateException("Redis unavailable")).when(claims).acquire(anyString(), any());
    doThrow(new IllegalStateException("Redis unavailable")).when(claims).clearTerminal(anyString());
    when(retrieval.retrieve(any(), any())).thenReturn(RagContextSnapshot.notConfigured());
    validDeck = new PreparedQuestionDeck(List.of(
        new PreparedQuestionDeck.PreparedQuestion(InterviewPhase.FUNDAMENTALS, 1, "Idempotency",
            "How do you prevent duplicate writes?", List.of("unique key"), "Idempotency", List.of("idempotency"),
            GroundingMode.GENERAL, List.of(), List.of(new RubricPoint("identity", "business key"),
                new RubricPoint("atomicity", "conditional update")), "What happens after a crash?")));
    when(rubrics.generate(any(), any(), any())).thenReturn(validDeck);
  }

  @AfterEach void neverUsesRedisClaims() { verifyNoInteractions(claims); }

  @ParameterizedTest @ValueSource(strings = {"success", "invalid", "runtime"})
  void aNewCommittedAttemptFencesTheOldResultEvenWhileTheTaskIsStillPublished(String oldResult) throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(generator.generate(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        if (oldResult.equals("invalid")) throw new InvalidQuestionDeckException("old invalid output");
        if (oldResult.equals("runtime")) throw new IllegalStateException("old model failure");
        return skeletons();
      }
      throw new IllegalStateException("second worker model failure");
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> handler.prepare(message));
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        String oldToken = currentTask().getExecutionToken();
        expireLease();
        assertThatThrownBy(() -> handler.prepare(message))
            .isInstanceOfSatisfying(QuestionPreparationExecutionException.class,
                error -> assertThat(error.attemptGeneration()).isEqualTo(2));
        assertThat(handler.markInvalid(message, 1, oldToken)).isFalse();
        assertThat(handler.markDead(message, 1, oldToken)).isFalse();
        assertThat(handler.markInvalid(message, 2, oldToken)).isFalse();
        handler.releaseForRetry(message, 2, oldToken);
        assertThat(currentTask().getExecutionToken()).isNotEqualTo(oldToken).isNotNull();
      } finally {
        release.countDown();
      }
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(QuestionPreparationHandler.Outcome.STALE);
    }
    var task = tasks.findByTaskId(message.taskId()).orElseThrow();
    assertThat(task.getAttemptCount()).isEqualTo(2);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.PREPARING);
    assertThat(cards.countBySessionId(session.getId())).isZero();
    // A subsequent valid retry commits the cards and task state together.
    handler.releaseForRetry(message, task.getAttemptCount(), task.getExecutionToken());
    doReturn(skeletons()).when(generator).generate(any());
    assertThat(handler.prepare(message)).isEqualTo(QuestionPreparationHandler.Outcome.COMPLETED);
    assertThat(tasks.findByTaskId(message.taskId()).orElseThrow().getAttemptCount()).isEqualTo(3);
    assertThat(cards.countBySessionId(session.getId())).isEqualTo(2);
  }

  @Test void inspectionDoesNotUpdateThePersistentTaskVersionOrAttemptCount() {
    var before = tasks.findByTaskId(message.taskId()).orElseThrow();
    handler.inspect(message);
    var after = tasks.findByTaskId(message.taskId()).orElseThrow();
    assertThat(after.getAttemptCount()).isZero();
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  @Test void duplicateDeliveryCannotStartGenerationWhileDatabaseOwnerIsActive() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(generator.generate(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
      }
      return skeletons();
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> handler.prepare(message));
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        var duplicate = handler.prepare(message);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(duplicate).isEqualTo(QuestionPreparationHandler.Outcome.BUSY);
      } finally {
        release.countDown();
      }
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(QuestionPreparationHandler.Outcome.COMPLETED);
    }
  }

  @Test void simultaneousClaimsHaveExactlyOneGenerationAndOneDeck() throws Exception {
    int workers = 6;
    var ready = new CountDownLatch(workers);
    var start = new CountDownLatch(1);
    var losersFinished = new CountDownLatch(workers - 1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(generator.generate(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      calls.incrementAndGet();
      assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
      return skeletons();
    });
    try (var executor = Executors.newFixedThreadPool(workers)) {
      var results = new ArrayList<Future<QuestionPreparationHandler.Outcome>>();
      for (int i = 0; i < workers; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
          try { return handler.prepare(message); }
          finally { losersFinished.countDown(); }
        }));
      }
      try {
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(losersFinished.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
      } finally {
        start.countDown();
        release.countDown();
      }
      var outcomes = new ArrayList<QuestionPreparationHandler.Outcome>();
      for (var result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
      assertThat(outcomes).containsOnly(QuestionPreparationHandler.Outcome.COMPLETED, QuestionPreparationHandler.Outcome.BUSY);
      assertThat(outcomes.stream().filter(value -> value == QuestionPreparationHandler.Outcome.COMPLETED).count()).isEqualTo(1);
      assertThat(currentTask().getAttemptCount()).isEqualTo(1);
      assertThat(cards.countBySessionId(session.getId())).isEqualTo(2);
    }
  }

  @Test void aFailureAfterWritingCardsRollsBackTheWholeDeckAndStateTransition() {
    when(generator.generate(any())).thenReturn(skeletons());
    var first = validDeck.questions().getFirst();
    var missingSnapshot = new PreparedQuestionDeck.PreparedQuestion(InterviewPhase.FUNDAMENTALS, 2,
        first.topic(), first.question(), first.focusPoints(), first.knowledgePoint(), first.retrievalKeywords(),
        first.groundingMode(), first.evidenceRefs(), first.rubric(), first.fallbackFollowUp());
    when(rubrics.generate(any(), any(), any())).thenReturn(new PreparedQuestionDeck(List.of(first, missingSnapshot)));

    assertThatThrownBy(() -> handler.prepare(message)).isInstanceOfSatisfying(
        QuestionPreparationExecutionException.class, error -> {
          assertThat(error.getCause()).isInstanceOf(InvalidQuestionDeckException.class);
          assertThat(error.attemptGeneration()).isEqualTo(1);
          assertThat(error.executionToken()).isEqualTo(currentTask().getExecutionToken());
        });
    assertThat(cards.countBySessionId(session.getId())).isZero();
    assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.PREPARING);
    var failed = currentTask();
    assertThat(handler.markInvalid(message, failed.getAttemptCount(), failed.getExecutionToken())).isTrue();
    assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.PREPARATION_FAILED);
  }

  @Test void rabbitRetryCompletesTheDeckWithoutRedis() throws Exception {
    when(generator.generate(any())).thenThrow(new IllegalStateException("transient model failure"))
        .thenReturn(skeletons());
    publisher.publish(message);
    await(() -> currentTask().getStatus() == AsyncTaskStatus.COMPLETED, 15);
    assertThat(currentTask().getAttemptCount()).isEqualTo(2);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(currentTask().getExecutionLeaseUntil()).isNull();
    assertThat(cards.countBySessionId(session.getId())).isEqualTo(2);
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.READY);
    assertThat(handler.prepare(message)).isEqualTo(QuestionPreparationHandler.Outcome.STALE);
    verify(generator, times(2)).generate(any());
  }

  @Test void crashedOwnerIsDeferredAndTakenOverAfterLeaseExpiry() throws Exception {
    jdbc.update("update async_task set status='PUBLISHED', attempt_count=1, execution_token='crashed-owner', "
        + "execution_lease_until=timestampadd(minute,11,current_timestamp(6)) where task_id=?", message.taskId().toString());
    when(generator.generate(any())).thenReturn(skeletons());
    publisher.publish(message);
    await(() -> rabbitAdmin.getQueueInfo(RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE + ".retry.2")
        .getMessageCount() == 1, 5);
    verifyNoInteractions(generator);
    expireLease();
    await(() -> currentTask().getStatus() == AsyncTaskStatus.COMPLETED, 40);
    assertThat(currentTask().getAttemptCount()).isEqualTo(2);
    assertThat(cards.countBySessionId(session.getId())).isEqualTo(2);
  }

  @Test void deadLetterAndManualRetryFenceTheOldEpochWithoutRedis() throws Exception {
    when(generator.generate(any())).thenThrow(new IllegalStateException("model unavailable"));
    rabbit.convertAndSend(RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_EXCHANGE, "interview.preparation",
        message, source -> {
          source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
          return source;
        });
    await(() -> currentTask().getStatus() == AsyncTaskStatus.DEAD, 10);
    assertThat(rabbit.receiveAndConvert(RabbitTopologyConfig.INTERVIEW_PREPARATION_DLQ, 5000)).isEqualTo(message);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(cards.countBySessionId(session.getId())).isZero();
    var user = users.findById(session.getUserAccountId()).orElseThrow();
    taskService.retry(new CurrentUser(user.getId(), user.getUserId(), user.getEmail(), user.getDisplayName()),
        message.taskId(), java.util.UUID.randomUUID());
    assertThat(currentTask().getExecutionEpoch()).isEqualTo(1);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(handler.prepare(message)).isEqualTo(QuestionPreparationHandler.Outcome.STALE);
    doReturn(skeletons()).when(generator).generate(any());
    publisher.publish(new TaskMessage(message.taskId(), message.taskType(), message.bizKey(), 1));
    await(() -> currentTask().getStatus() == AsyncTaskStatus.COMPLETED, 10);
    assertThat(cards.countBySessionId(session.getId())).isEqualTo(2);
    verify(generator, times(2)).generate(any());
  }

  @Test void invalidModelOutputTerminatesWithoutPublishingARetry() throws Exception {
    when(generator.generate(any())).thenThrow(new InvalidQuestionDeckException("invalid output"));
    publisher.publish(message);
    await(() -> currentTask().getStatus() == AsyncTaskStatus.FAILED, 10);
    assertThat(currentTask().getAttemptCount()).isEqualTo(1);
    assertThat(currentTask().getExecutionToken()).isNull();
    for (int retry = 1; retry <= 3; retry++) {
      assertThat(rabbitAdmin.getQueueInfo(RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE + ".retry." + retry)
          .getMessageCount()).isZero();
    }
  }

  private void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Timed out waiting for question preparation");
  }

  private List<QuestionSkeletonOutput.Skeleton> skeletons() {
    return List.of(new QuestionSkeletonOutput.Skeleton(InterviewPhase.FUNDAMENTALS, 1, "Idempotency",
        "How do you prevent duplicate writes?", List.of("unique key"), "Idempotency", List.of("idempotency"),
        "What happens after a crash?"));
  }

  private AsyncTaskEntity currentTask() { return tasks.findByTaskId(message.taskId()).orElseThrow(); }

  private void expireLease() {
    jdbc.update("update async_task set execution_lease_until=timestampadd(second,-1,current_timestamp(6)) where task_id=?",
        message.taskId().toString());
  }
}
