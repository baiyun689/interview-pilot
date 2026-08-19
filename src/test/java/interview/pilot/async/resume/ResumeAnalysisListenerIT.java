package interview.pilot.async.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
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

import interview.pilot.ai.AiGatewayException;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.application.AsyncTaskService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.resume.application.ResumeProfiler;
import interview.pilot.resume.application.ResumeAnalysisHandler;
import interview.pilot.resume.domain.ResumeAnalysisResult;
import interview.pilot.resume.domain.ResumeEvaluation;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@SpringBootTest(properties = "app.async.rabbit.dispatch-initial-delay=1h")
@Testcontainers
class ResumeAnalysisListenerIT {
  private static final String REDIS_KEY_PREFIX = "interview-pilot:processing:";

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_resume_listener");

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

  @MockitoBean
  private ResumeProfiler profiler;

  @MockitoSpyBean
  private ResumeAnalysisHandler handler;

  @Autowired
  private ResumeRepository resumeRepository;

  @Autowired
  private AsyncTaskRepository taskRepository;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @MockitoSpyBean
  private ProcessingClaim processingClaim;

  @Autowired
  private RedissonClient redis;

  @Autowired
  private AsyncTaskService taskService;

  @BeforeEach
  void resetState() {
    taskRepository.deleteAll();
    resumeRepository.deleteAll();
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE, true);
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.RESUME_ANALYSIS_DLQ, true);
    for (int retry = 1; retry <= 3; retry++) {
      rabbitAdmin.purgeQueue(
          RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry." + retry, true);
    }
    redis.getKeys().deleteByPattern(REDIS_KEY_PREFIX + "resume-analysis:*");
  }

  @Test
  void consumesMainQueuePersistsProfileThenCompletesOwnedClaim() throws Exception {
    Work work = pendingWork();
    when(profiler.analyze(work.resume().getParsedText())).thenReturn(validResult());

    send(work.message());

    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    ResumeEntity completed = resumeRepository.findById(work.resume().getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(ResumeStatus.READY);
    assertThat(completed.getEvaluationSnapshot()).contains("overallScore");
    assertThat(redis.getBucket(claimKey(work.resume().getId()), StringCodec.INSTANCE).get())
        .asString().startsWith("done:");
  }

  @Test
  void mysqlSuccessWinsBeforeClaimAcquisitionForDuplicateMessage() throws Exception {
    Work work = pendingWork();
    work.resume().setStatus(ResumeStatus.ANALYZING);
    work.resume().setStatus(ResumeStatus.READY);
    work.task().setStatus(AsyncTaskStatus.COMPLETED);
    resumeRepository.saveAndFlush(work.resume());
    taskRepository.saveAndFlush(work.task());
    String claimBusinessKey = "resume-analysis:" + work.resume().getId();
    String otherOwner = processingClaim.acquire(claimBusinessKey, Duration.ofMinutes(5))
        .orElseThrow();

    send(work.message());
    await(() -> rabbitAdmin.getQueueInfo(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE).getMessageCount() == 0,
        Duration.ofSeconds(5));

    verify(profiler, never()).analyze(org.mockito.ArgumentMatchers.anyString());
    assertThat(redis.getBucket(claimKey(work.resume().getId()), StringCodec.INSTANCE).get())
        .isEqualTo(otherOwner);
  }

  @Test
  void retryableFailureReleasesOwnedClaimAndEntersSharedRetryPipeline() throws Exception {
    Work work = pendingWork();
    when(profiler.analyze(work.resume().getParsedText()))
        .thenThrow(new AiGatewayException("do-not-log-this-provider-output"));

    send(work.message());

    Object retry = rabbitTemplate.receiveAndConvert(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry.1", 10_000);
    assertThat(retry).isEqualTo(work.message());
    await(() -> taskStatus(work) == AsyncTaskStatus.PUBLISHED, Duration.ofSeconds(5));
    assertThat(resumeRepository.findById(work.resume().getId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.ANALYZING);
    assertThat(redis.getBucket(claimKey(work.resume().getId()), StringCodec.INSTANCE).isExists())
        .isFalse();
  }

  @Test
  void occupiedClaimExpiresAndTheDelayedDeliveryEventuallyCompletesTheWork() throws Exception {
    Work work = pendingWork();
    when(profiler.analyze(work.resume().getParsedText())).thenReturn(validResult());
    processingClaim.acquire(
        "resume-analysis:" + work.resume().getId(), Duration.ofSeconds(2)).orElseThrow();

    send(work.message());

    await(() -> rabbitAdmin.getQueueInfo(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry.1").getMessageCount() == 1,
        Duration.ofSeconds(3));
    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    assertThat(resumeRepository.findById(work.resume().getId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.READY);
  }

  @Test
  void productionClaimExpiresBeforeTheThirdRetryReturns() {
    Duration totalDelayBeforeThirdRetry = Duration.ofSeconds(5 + 30 + 120);

    assertThat(ResumeAnalysisListener.PROCESSING_TTL).isLessThan(totalDelayBeforeThirdRetry);
  }

  @Test
  void staleHandlerOutcomeDoesNotCompleteAClaimOrPublishAnotherRetry() {
    Work work = pendingWork();
    org.mockito.Mockito.doReturn(ResumeAnalysisHandler.Outcome.STALE)
        .when(handler).handle(work.message());

    send(work.message());

    verify(handler, org.mockito.Mockito.timeout(5_000)).handle(work.message());
    assertThat(rabbitTemplate.receive(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry.1", 500)).isNull();
    assertThat(redis.getBucket(
        claimKey(work.resume().getId()), StringCodec.INSTANCE).isExists()).isFalse();
  }

  @Test
  void lostClaimAfterDurableTerminalResultDoesNotPublishAFalseRetry() {
    Work work = pendingWork();
    org.mockito.Mockito.doAnswer(invocation -> {
      ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
      AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
      resume.setStatus(ResumeStatus.ANALYZING);
      resume.setStatus(ResumeStatus.READY);
      task.setStatus(AsyncTaskStatus.COMPLETED);
      resumeRepository.saveAndFlush(resume);
      taskRepository.saveAndFlush(task);
      return ResumeAnalysisHandler.Outcome.TERMINAL;
    }).when(handler).handle(work.message());
    org.mockito.Mockito.doReturn(false).when(processingClaim).complete(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(Duration.class));

    send(work.message());

    verify(handler, org.mockito.Mockito.timeout(5_000)).handle(work.message());
    assertThat(rabbitTemplate.receive(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry.1", 500)).isNull();
    assertThat(taskStatus(work)).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(resumeRepository.findById(work.resume().getId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.READY);
  }

  @Test
  void retryDeliveryRecoversAnAnalyzingResumeAfterTheFirstAiAttemptFails() throws Exception {
    Work work = pendingWork();
    when(profiler.analyze(work.resume().getParsedText()))
        .thenThrow(new AiGatewayException("transient"))
        .thenReturn(validResult());

    send(work.message());

    await(() -> taskStatus(work) == AsyncTaskStatus.COMPLETED, Duration.ofSeconds(10));
    assertThat(resumeRepository.findById(work.resume().getId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.READY);
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getAttemptCount())
        .isEqualTo(2);
  }

  @Test
  void exhaustedRetryPublishesDeadLetterThenMarksTaskAndResumeDead() throws Exception {
    Work work = pendingWork();
    when(profiler.analyze(work.resume().getParsedText()))
        .thenThrow(new AiGatewayException("provider secret"));

    rabbitTemplate.convertAndSend(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_EXCHANGE,
        "resume.analysis",
        work.message(),
        message -> {
          message.getMessageProperties().setHeader("x-retry-count", 3);
          return message;
        });

    await(() -> taskStatus(work) == AsyncTaskStatus.DEAD, Duration.ofSeconds(10));
    assertThat(rabbitTemplate.receiveAndConvert(
        RabbitTopologyConfig.RESUME_ANALYSIS_DLQ, 5_000)).isEqualTo(work.message());
    assertThat(resumeRepository.findById(work.resume().getId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.FAILED);
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getLastError())
        .isEqualTo("Resume analysis retries exhausted");
  }

  @Test
  void manualRetryRestoresDeadResumeAndClearsOnlyTerminalClaim() {
    Work work = pendingWork();
    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    resume.setStatus(ResumeStatus.FAILED);
    resume.setFailureReason("Resume analysis retries exhausted");
    resume.setSkillsSnapshot("{\"unsafe\":true}");
    resume.setEvaluationSnapshot("{\"unsafe\":true}");
    resumeRepository.saveAndFlush(resume);
    AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setAttemptCount(4);
    task.setPublishAttempts(6);
    task.setLastError("Resume analysis retries exhausted");
    taskRepository.saveAndFlush(task);
    String key = "resume-analysis:" + resume.getId();
    String token = processingClaim.acquire(key, Duration.ofMinutes(1)).orElseThrow();
    processingClaim.complete(key, token, Duration.ofHours(1));

    var retried = taskService.retry(owner(), task.getTaskId(), UUID.randomUUID());

    assertThat(retried.status()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(retried.attemptCount()).isEqualTo(4);
    assertThat(retried.publishAttempts()).isEqualTo(6);
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getExecutionEpoch())
        .isEqualTo(1);
    ResumeEntity resetResume = resumeRepository.findById(resume.getId()).orElseThrow();
    assertThat(resetResume.getStatus()).isEqualTo(ResumeStatus.PENDING);
    assertThat(resetResume.getFailureReason()).isNull();
    assertThat(resetResume.getSkillsSnapshot()).isNull();
    assertThat(resetResume.getEvaluationSnapshot()).isNull();
  }

  @Test
  void inspectedOldDeliveryAfterManualRetryReleasesItsClaimForTheNewEpoch() throws Exception {
    Work work = pendingWork();
    CountDownLatch inspected = new CountDownLatch(1);
    CountDownLatch continueOldDelivery = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(invocation -> {
      ResumeAnalysisHandler.ResumeAnalysisTarget target =
          (ResumeAnalysisHandler.ResumeAnalysisTarget) invocation.callRealMethod();
      inspected.countDown();
      continueOldDelivery.await(10, TimeUnit.SECONDS);
      return target;
    }).when(handler).inspect(work.message());

    send(work.message());
    assertThat(inspected.await(10, TimeUnit.SECONDS)).isTrue();
    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    resume.setStatus(ResumeStatus.FAILED);
    resume.setFailureReason("simulated terminal owner");
    resumeRepository.saveAndFlush(resume);
    AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
    task.setStatus(AsyncTaskStatus.DEAD);
    taskRepository.saveAndFlush(task);
    taskService.retry(owner(), task.getTaskId(), UUID.randomUUID());

    continueOldDelivery.countDown();
    verify(handler, org.mockito.Mockito.timeout(10_000)).handle(work.message());
    String claimBusinessKey = "resume-analysis:" + resume.getId();
    await(() -> !redis.getBucket(
        REDIS_KEY_PREFIX + claimBusinessKey, StringCodec.INSTANCE).isExists(),
        Duration.ofSeconds(5));

    assertThat(taskRepository.findById(task.getId()).orElseThrow().getExecutionEpoch())
        .isEqualTo(1);
    assertThat(processingClaim.acquire(claimBusinessKey, Duration.ofMinutes(1))).isPresent();
  }

  @Test
  void rejectsMessageIdentityThatDoesNotMatchMysql() {
    Work work = pendingWork();
    TaskMessage tampered = new TaskMessage(
        work.task().getTaskId(), AsyncTaskType.RESUME_ANALYSIS, "resume:999999");

    send(tampered);

    Object retry = rabbitTemplate.receiveAndConvert(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE + ".retry.1", 10_000);
    assertThat(retry).isEqualTo(tampered);
    verify(profiler, never()).analyze(org.mockito.ArgumentMatchers.anyString());
    assertThat(taskStatus(work)).isEqualTo(AsyncTaskStatus.PENDING);
  }

  private void send(TaskMessage message) {
    rabbitTemplate.convertAndSend(
        RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_EXCHANGE,
        "resume.analysis",
        message);
  }

  private Work pendingWork() {
    ResumeEntity resume = resumeRepository.saveAndFlush(ResumeEntity.pending(1L,
        "candidate.txt",
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""),
        "Built a Payments API using Java and Spring Boot."));
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.RESUME_ANALYSIS,
        "resume:" + resume.getId(),
        "{\"resumeId\":" + resume.getId() + "}");
    task.setTaskId(UUID.randomUUID());
    task = taskRepository.saveAndFlush(task);
    return new Work(resume, task,
        new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey()));
  }

  private ResumeProfile validProfile() {
    return new ResumeProfile(
        "Java engineer",
        List.of("Java", "Spring Boot"),
        List.of(new ResumeProfile.ProjectEvidence(
            "Payments API", "Built an API", List.of("Spring Boot"))),
        List.of("Backend engineering"),
        List.of("Scale not stated"));
  }

  private ResumeEvaluation validEvaluation() {
    return new ResumeEvaluation(
        78,
        new ResumeEvaluation.ScoreDetail(30, 14, 12, 13, 9),
        List.of());
  }

  private ResumeAnalysisResult validResult() {
    return new ResumeAnalysisResult(validProfile(), validEvaluation());
  }

  private static CurrentUser owner() {
    return new CurrentUser(1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  }

  private AsyncTaskStatus taskStatus(Work work) {
    return taskRepository.findById(work.task().getId()).orElseThrow().getStatus();
  }

  private String claimKey(Long resumeId) {
    return REDIS_KEY_PREFIX + "resume-analysis:" + resumeId;
  }

  private void await(BooleanSupplier condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Timed out waiting for asynchronous resume analysis");
  }

  private record Work(ResumeEntity resume, AsyncTaskEntity task, TaskMessage message) {}
}
