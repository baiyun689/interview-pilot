package interview.pilot.async.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.application.AsyncTaskService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.interview.application.AnswerProcessingResult;
import interview.pilot.interview.application.ReportEvidence;
import interview.pilot.interview.application.ReportGenerator;
import interview.pilot.interview.application.InterviewReportHandler;
import interview.pilot.interview.application.StoredAnswerResultCodec;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(properties = "app.async.rabbit.dispatch-initial-delay=1h")
@Testcontainers
class InterviewReportListenerIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("report_listener");
  @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(
      DockerImageName.parse("rabbitmq:4-management"));
  @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
      DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @MockitoBean ReportGenerator generator;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewReportRepository reports;
  @Autowired InterviewTurnRepository turns;
  @Autowired InterviewSessionRepository sessions;
  @Autowired JobProfileRepository jobs;
  @Autowired ResumeRepository resumes;
  @Autowired StoredAnswerResultCodec answerCodec;
  @Autowired ObjectMapper objectMapper;
  @Autowired RabbitTemplate rabbit;
  @Autowired RabbitAdmin admin;
  @Autowired RedissonClient redis;
  @Autowired MeterRegistry meters;
  @Autowired ProcessingClaim claims;
  @Autowired AsyncTaskService taskService;
  @Autowired UserAccountRepository users;
  @MockitoSpyBean InterviewReportHandler handler;

  @BeforeEach
  void clean() {
    reports.deleteAll();
    tasks.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    admin.purgeQueue(RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE, true);
    admin.purgeQueue(RabbitTopologyConfig.INTERVIEW_REPORT_DLQ, true);
    for (int retry = 1; retry <= 3; retry++) {
      admin.purgeQueue(RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE + ".retry." + retry, true);
    }
    redis.getKeys().deleteByPattern("interview-pilot:processing:interview-report:*");
    org.mockito.Mockito.reset(generator);
  }

  @Test
  void duplicateMessagesUsePersistedProviderSnapshotAndCreateOneImmutableReport() throws Exception {
    double completedBefore = count("interview_pilot.tasks.completed", "task_type", "interview_evaluation");
    Work work = completedInterview();
    when(generator.generate(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), anyList(),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          List<ReportEvidence> evidence = invocation.getArgument(2);
          assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.competency()).isEqualTo("Java");
            assertThat(item.evidence()).containsExactly("Version check");
          });
          return validReport();
        });

    send(work.message(), null);
    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    send(work.message(), null);
    await(() -> admin.getQueueInfo(
        RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE).getMessageCount() == 0,
        Duration.ofSeconds(5));

    verify(generator, times(1)).generate(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), anyList(),
        org.mockito.ArgumentMatchers.argThat(skill -> "custom".equals(skill.id())));
    assertThat(reports.count()).isEqualTo(1);
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.COMPLETED);
    assertThat(turns.count()).isEqualTo(1);
    assertThat(count("interview_pilot.tasks.completed", "task_type", "interview_evaluation"))
        .isEqualTo(completedBefore + 1);
  }

  @Test
  void invalidOutputIsTerminalFailedWhileSessionAndTurnsRemainEvaluating() throws Exception {
    double failedBefore = count(
        "interview_pilot.tasks.failed", "task_type", "interview_evaluation", "status", "failed");
    Work work = completedInterview();
    when(generator.generate(anyString(), anyString(), anyList(),
        org.mockito.ArgumentMatchers.any()))
        .thenThrow(new AiStructuredOutputException("provider output secret"));

    send(work.message(), null);
    await(() -> taskStatus(work) == AsyncTaskStatus.FAILED, Duration.ofSeconds(10));

    assertThat(tasks.findById(work.taskId()).orElseThrow().getLastError())
        .isEqualTo("Interview report response was invalid");
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
    assertThat(reports.count()).isZero();
    assertThat(turns.count()).isEqualTo(1);
    assertThat(count("interview_pilot.tasks.failed", "task_type", "interview_evaluation",
        "status", "failed")).isEqualTo(failedBefore + 1);
  }

  private double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void exhaustedRetryPublishesDlqBeforeTaskBecomesDead() throws Exception {
    Work work = completedInterview();
    when(generator.generate(anyString(), anyString(), anyList(),
        org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("provider secret"));

    send(work.message(), 3);
    await(() -> taskStatus(work) == AsyncTaskStatus.DEAD, Duration.ofSeconds(10));

    assertThat(rabbit.receiveAndConvert(RabbitTopologyConfig.INTERVIEW_REPORT_DLQ, 5_000))
        .isEqualTo(work.message());
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
    assertThat(turns.count()).isEqualTo(1);
    assertThat(reports.count()).isZero();
  }

  @Test
  void manualRetryClearsDoneMarkerBeforeResetAndPreservesCounters() {
    Work work = completedInterview();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setAttemptCount(4);
    task.setPublishAttempts(7);
    task.setLastError("Interview report generation retries exhausted");
    tasks.saveAndFlush(task);
    String key = "interview-report:" + work.sessionId();
    String token = claims.acquire(key, Duration.ofMinutes(1)).orElseThrow();
    claims.complete(key, token, Duration.ofHours(1));

    var retried = taskService.retry(owner(), task.getTaskId(), UUID.randomUUID());

    assertThat(retried.status()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(retried.attemptCount()).isEqualTo(4);
    assertThat(retried.publishAttempts()).isEqualTo(7);
    assertThat(retried.error()).isNull();
    assertThat(tasks.findById(work.taskId()).orElseThrow().getExecutionEpoch()).isEqualTo(1);
    assertThat(redis.getBucket(
        "interview-pilot:processing:" + key,
        org.redisson.client.codec.StringCodec.INSTANCE).isExists()).isFalse();
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
  }

  @Test
  void manualRetryNeverDeletesActiveOwnerOrChangesMysql() {
    Work work = completedInterview();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    tasks.saveAndFlush(task);
    String key = "interview-report:" + work.sessionId();
    claims.acquire(key, Duration.ofMinutes(1)).orElseThrow();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> taskService.retry(owner(), task.getTaskId(), UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Task processing is still active");
    assertThat(tasks.findById(work.taskId()).orElseThrow().getStatus())
        .isEqualTo(AsyncTaskStatus.DEAD);
  }

  @Test
  void staleAiSuccessCannotOverwriteANewerAttemptGeneration() throws Exception {
    Work work = completedInterview();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(generator.generate(anyString(), anyString(), anyList(),
        org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      entered.countDown();
      release.await(10, TimeUnit.SECONDS);
      return validReport();
    });
    CompletableFuture<InterviewReportHandler.Outcome> old = CompletableFuture.supplyAsync(
        () -> handler.handle(work.message().taskId()));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    tasks.saveAndFlush(task);

    release.countDown();

    assertThat(old.get(10, TimeUnit.SECONDS)).isEqualTo(InterviewReportHandler.Outcome.STALE);
    assertThat(reports.count()).isZero();
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
  }

  @Test
  void corruptCompletedEvidenceExhaustionDeadLettersAndMakesTaskDeadWithoutDataLoss()
      throws Exception {
    Work work = completedInterview();
    var session = sessions.findBySessionId(work.sessionId()).orElseThrow();
    var turn = turns.findAllBySessionIdOrderByTurnNo(session.getId()).getFirst();
    turn.setEvaluationSnapshot("{}");
    turns.saveAndFlush(turn);

    send(work.message(), 3);

    await(() -> taskStatus(work) == AsyncTaskStatus.DEAD, Duration.ofSeconds(10));
    assertThat(rabbit.receiveAndConvert(RabbitTopologyConfig.INTERVIEW_REPORT_DLQ, 5_000))
        .isEqualTo(work.message());
    assertThat(sessions.findBySessionId(work.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
    assertThat(turns.findAllBySessionIdOrderByTurnNo(session.getId()))
        .singleElement().satisfies(stored -> {
          assertThat(stored.getStatus()).isEqualTo(TurnStatus.COMPLETED);
          assertThat(stored.getEvaluationSnapshot()).isEqualTo("{}");
        });
    verify(generator, never()).generate(anyString(), anyString(), anyList());
  }

  @Test
  void oldEpochDeliveryAfterManualRetryCannotKillTheNewGeneration() throws Exception {
    Work work = completedInterview();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    tasks.saveAndFlush(task);
    taskService.retry(owner(), task.getTaskId(), UUID.randomUUID());
    assertThat(tasks.findById(work.taskId()).orElseThrow().getExecutionEpoch()).isEqualTo(1);

    send(work.message(), 3);
    await(() -> admin.getQueueInfo(
        RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE).getMessageCount() == 0,
        Duration.ofSeconds(5));

    AsyncTaskEntity current = tasks.findById(work.taskId()).orElseThrow();
    assertThat(current.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(current.getExecutionEpoch()).isEqualTo(1);
    assertThat(rabbit.receive(RabbitTopologyConfig.INTERVIEW_REPORT_DLQ, 500)).isNull();
    assertThat(reports.count()).isZero();
  }

  @Test
  void handlerClassifiesEpochMismatchAsStaleNotTerminal() {
    Work work = completedInterview();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setExecutionEpoch(1);
    tasks.saveAndFlush(task);

    assertThat(handler.handle(work.message())).isEqualTo(InterviewReportHandler.Outcome.STALE);
    verify(generator, never()).generate(anyString(), anyString(), anyList());
  }

  @Test
  void inspectedOldDeliveryAfterManualRetryReleasesItsClaimForTheNewEpoch() throws Exception {
    Work work = completedInterview();
    CountDownLatch inspected = new CountDownLatch(1);
    CountDownLatch continueOldDelivery = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(invocation -> {
      InterviewReportHandler.Target target = (InterviewReportHandler.Target) invocation.callRealMethod();
      inspected.countDown();
      continueOldDelivery.await(10, TimeUnit.SECONDS);
      return target;
    }).when(handler).inspect(work.message());

    send(work.message(), null);
    assertThat(inspected.await(10, TimeUnit.SECONDS)).isTrue();
    AsyncTaskEntity task = tasks.findById(work.taskId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    tasks.saveAndFlush(task);
    taskService.retry(owner(), task.getTaskId(), UUID.randomUUID());

    continueOldDelivery.countDown();
    verify(handler, org.mockito.Mockito.timeout(10_000)).handle(work.message());
    String claimKey = "interview-report:" + work.sessionId();
    await(() -> !redis.getBucket(
        "interview-pilot:processing:" + claimKey,
        org.redisson.client.codec.StringCodec.INSTANCE).isExists(), Duration.ofSeconds(5));

    assertThat(tasks.findById(work.taskId()).orElseThrow().getExecutionEpoch()).isEqualTo(1);
    assertThat(claims.acquire(claimKey, Duration.ofMinutes(1))).isPresent();
  }

  private Work completedInterview() {
    ResumeEntity resume = resumes.saveAndFlush(ResumeEntity.pending(1L,
        "candidate.txt", UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""), "Java"));
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        "Backend", "Java", "{\"competencies\":[\"Java\"],\"preferredSkills\":[]}"));
    InterviewSessionEntity session = InterviewSessionEntity.create(
        resume.getId(), job.getId(), Difficulty.MEDIUM, 5,
        "deepseek", "deepseek-chat",
        write(new InterviewPlan(List.of("Java"), 5)));
    session.start();
    session.beginEvaluation();
    session = sessions.saveAndFlush(session);
    UUID requestId = UUID.randomUUID();
    var decision = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "Java", "done", "enough", 0.9);
    var evaluation = new AnswerEvaluation(
        88, "Strong answer", List.of("Version check"), List.of("Trade-offs"), decision);
    var result = new AnswerProcessingResult(
        session.getSessionId(), requestId, 1, evaluation, decision, null,
        Difficulty.MEDIUM, SessionStatus.EVALUATING, false);
    InterviewTurnEntity turn = InterviewTurnEntity.firstAsked(
        session.getId(), Difficulty.MEDIUM, "Explain optimistic locking", "Java");
    turn.setRequestId(requestId);
    turn.setAnswerText("Use a version column");
    turn.setFeedbackText(evaluation.feedback());
    turn.setScore(BigDecimal.valueOf(evaluation.score()));
    turn.setEvaluationSnapshot(answerCodec.write(result));
    turn.setAnsweredAt(Instant.now());
    turn.setStatus(TurnStatus.COMPLETED);
    turns.saveAndFlush(turn);
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + session.getSessionId(),
        "{\"sessionId\":\"" + session.getSessionId() + "\"}");
    task.setTaskId(UUID.randomUUID());
    task = tasks.saveAndFlush(task);
    return new Work(session.getSessionId(), task.getId(),
        new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey()));
  }

  private InterviewReport validReport() {
    return new InterviewReport(86, Map.of("Java", 88), List.of("Concurrency"),
        List.of("Trade-offs"), "Strong evidence-grounded result");
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new AssertionError(exception);
    }
  }

  private void send(TaskMessage message, Integer retryCount) {
    rabbit.convertAndSend(
        RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_EXCHANGE, "interview.report", message,
        source -> {
          if (retryCount != null) source.getMessageProperties().setHeader("x-retry-count", retryCount);
          return source;
        });
  }

  private AsyncTaskStatus taskStatus(Work work) {
    return tasks.findById(work.taskId()).orElseThrow().getStatus();
  }

  private void await(BooleanSupplier condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Timed out waiting for report processing");
  }

  private static String anyString() {
    return org.mockito.ArgumentMatchers.anyString();
  }

  @Test
  void taskOwnerMismatchCannotGenerateOrWriteAnotherUsersReport() {
    Work work = completedInterview();
    UserAccountEntity other = users.save(UserAccountEntity.register(
        "report-mismatch-" + UUID.randomUUID() + "@example.com", "!", "Other"));
    AsyncTaskEntity task = tasks.saveAndFlush(AsyncTaskEntity.pending(
        other.getId(), AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + work.sessionId(),
        "{\"sessionId\":\"" + work.sessionId() + "\"}"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(task.getTaskId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Interview report business key is invalid");
    verify(generator, never()).generate(anyString(), anyString(), anyList(),
        org.mockito.ArgumentMatchers.any());
    assertThat(reports.count()).isZero();
  }

  private static CurrentUser owner() {
    return new CurrentUser(1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  }

  private record Work(UUID sessionId, Long taskId, TaskMessage message) {}
}
