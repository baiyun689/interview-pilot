package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.ai.AiGatewayException;
import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@Testcontainers
class ResumeAnalysisHandlerTest {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_resume_analysis");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean
  private RedissonClient redissonClient;

  @MockitoBean
  private ResumeProfiler profiler;

  @Autowired
  private ResumeAnalysisHandler handler;

  @Autowired
  private ResumeQueryService queryService;

  @Autowired
  private ResumeRepository resumeRepository;

  @Autowired
  private AsyncTaskRepository taskRepository;

  @BeforeEach
  void cleanDatabase() {
    taskRepository.deleteAll();
    resumeRepository.deleteAll();
  }

  @Test
  void profilesPendingResumeAndCompletesTask() {
    Work work = pendingWork();
    when(profiler.profile(work.resume().getParsedText())).thenReturn(validProfile());

    handler.handle(work.task().getTaskId());

    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.READY);
    assertThat(resume.getSkillsSnapshot())
        .contains("Java", "Payments API", "Spring Boot");
    assertThat(resume.getFailureReason()).isNull();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastError()).isNull();
    assertThat(queryService.get(resume.getId()).profile()).isEqualTo(validProfile());
  }

  @Test
  void alreadyReadyResumeWithCompletedTaskIsANoOp() {
    Work work = pendingWork();
    work.resume().setStatus(ResumeStatus.ANALYZING);
    work.resume().setStatus(ResumeStatus.READY);
    work.task().setStatus(AsyncTaskStatus.COMPLETED);
    resumeRepository.saveAndFlush(work.resume());
    taskRepository.saveAndFlush(work.task());

    handler.handle(work.task().getTaskId());

    verify(profiler, never()).profile(org.mockito.ArgumentMatchers.anyString());
    assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getAttemptCount())
        .isZero();
  }

  @Test
  void oldMessageEpochIsStaleRatherThanTerminal() {
    Work work = pendingWork();
    work.task().setExecutionEpoch(1);
    taskRepository.saveAndFlush(work.task());
    TaskMessage old = new TaskMessage(
        work.task().getTaskId(), work.task().getTaskType(), work.task().getBizKey(), 0);

    assertThat(handler.handle(old)).isEqualTo(ResumeAnalysisHandler.Outcome.STALE);
    verify(profiler, never()).profile(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void retryableGatewayFailureLeavesWorkIncompleteAndStoresOnlySafeError() {
    Work work = pendingWork();
    when(profiler.profile(work.resume().getParsedText()))
        .thenThrow(new AiGatewayException("provider rejected secret-token-123"));

    assertThatThrownBy(() -> handler.handle(work.task().getTaskId()))
        .isInstanceOf(ResumeAnalysisRetryableException.class)
        .hasMessage("Resume analysis temporarily unavailable")
        .hasMessageNotContaining("secret-token-123");

    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.ANALYZING);
    assertThat(resume.getSkillsSnapshot()).isNull();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastError()).isEqualTo("Resume analysis temporarily unavailable");
  }

  @Test
  void terminalStructuredOutputFailureMarksBothRecordsFailedWithSanitizedReason() {
    Work work = pendingWork();
    when(profiler.profile(work.resume().getParsedText()))
        .thenThrow(new AiStructuredOutputException("model-output-secret"));

    handler.handle(work.task().getTaskId());

    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    AsyncTaskEntity task = taskRepository.findById(work.task().getId()).orElseThrow();
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.FAILED);
    assertThat(resume.getFailureReason()).isEqualTo("Resume profile response was invalid");
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(task.getLastError()).isEqualTo("Resume profile response was invalid");
    assertThat(resume.getFailureReason()).doesNotContain("model-output-secret");
  }

  @Test
  void invalidProfileFieldsAreTerminalAndNeverPersisted() {
    Work work = pendingWork();
    when(profiler.profile(work.resume().getParsedText()))
        .thenReturn(new ResumeProfile(" ", null, List.of(), List.of(), List.of()));

    handler.handle(work.task().getTaskId());

    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.FAILED);
    assertThat(resume.getSkillsSnapshot()).isNull();
    assertThat(resume.getFailureReason()).isEqualTo("Resume profile response was invalid");
  }

  @Test
  void nullProjectElementIsTerminalAndNeverPersisted() {
    Work work = pendingWork();
    when(profiler.profile(work.resume().getParsedText())).thenReturn(new ResumeProfile(
        "Java engineer",
        List.of("Java"),
        Arrays.asList((ResumeProfile.ProjectEvidence) null),
        List.of(),
        List.of()));

    handler.handle(work.task().getTaskId());

    ResumeEntity resume = resumeRepository.findById(work.resume().getId()).orElseThrow();
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.FAILED);
    assertThat(resume.getSkillsSnapshot()).isNull();
    assertThat(resume.getFailureReason()).isEqualTo("Resume profile response was invalid");
  }

  @Test
  void malformedStoredProfileRaisesOnlyASanitizedServiceError() {
    Work work = pendingWork();
    work.resume().setSkillsSnapshot("""
        {"summary":{"secretModelOutput":true},"technicalSkills":[],
        "projects":[],"strengths":[],"risks":[]}
        """);
    resumeRepository.saveAndFlush(work.resume());

    assertThatThrownBy(() -> queryService.get(work.resume().getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stored resume profile is invalid")
        .hasNoCause()
        .hasMessageNotContaining("secretModelOutput");
  }

  @Test
  void contractInvalidStoredProfileRaisesOnlyASanitizedServiceError() {
    Work work = pendingWork();
    work.resume().setSkillsSnapshot("""
        {"summary":"Java engineer","technicalSkills":["Java"],
        "projects":[null],"strengths":[],"risks":[]}
        """);
    resumeRepository.saveAndFlush(work.resume());

    assertThatThrownBy(() -> queryService.get(work.resume().getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stored resume profile is invalid")
        .hasNoCause();
  }

  @Test
  void staleSuccessCannotOverwriteANewerAttempt() throws Exception {
    assertStaleAttemptIsFenced(OldAttemptResult.SUCCESS);
  }

  @Test
  void staleInvalidOutputCannotFailANewerAttempt() throws Exception {
    assertStaleAttemptIsFenced(OldAttemptResult.INVALID_OUTPUT);
  }

  @Test
  void staleRuntimeFailureCannotRecordErrorOrRetryANewerAttempt() throws Exception {
    assertStaleAttemptIsFenced(OldAttemptResult.RUNTIME_FAILURE);
  }

  private void assertStaleAttemptIsFenced(OldAttemptResult oldResult) throws Exception {
    Work work = pendingWork();
    var call = new AtomicInteger();
    var oldEntered = new CountDownLatch(1);
    var newEntered = new CountDownLatch(1);
    var releaseOld = new CountDownLatch(1);
    var releaseNew = new CountDownLatch(1);
    ResumeProfile newerProfile = new ResumeProfile(
        "New owner profile", List.of("Java 21"), List.of(), List.of(), List.of());
    doAnswer(invocation -> {
      int attempt = call.incrementAndGet();
      if (attempt == 1) {
        oldEntered.countDown();
        assertThat(releaseOld.await(10, TimeUnit.SECONDS)).isTrue();
        return switch (oldResult) {
          case SUCCESS -> new ResumeProfile(
              "Stale owner profile", List.of("Java 8"), List.of(), List.of(), List.of());
          case INVALID_OUTPUT -> throw new AiStructuredOutputException("stale-model-output");
          case RUNTIME_FAILURE -> throw new AiGatewayException("stale-provider-error");
        };
      }
      newEntered.countDown();
      assertThat(releaseNew.await(10, TimeUnit.SECONDS)).isTrue();
      return newerProfile;
    }).when(profiler).profile(work.resume().getParsedText());

    var executor = Executors.newFixedThreadPool(2);
    try {
      var oldOwner = executor.submit(() -> handler.handle(work.task().getTaskId()));
      assertThat(oldEntered.await(10, TimeUnit.SECONDS)).isTrue();
      var newOwner = executor.submit(() -> handler.handle(work.task().getTaskId()));
      assertThat(newEntered.await(10, TimeUnit.SECONDS)).isTrue();

      releaseOld.countDown();
      assertThat(oldOwner.get(10, TimeUnit.SECONDS))
          .isEqualTo(ResumeAnalysisHandler.Outcome.STALE);

      AsyncTaskEntity taskWhileNewRuns =
          taskRepository.findById(work.task().getId()).orElseThrow();
      ResumeEntity resumeWhileNewRuns =
          resumeRepository.findById(work.resume().getId()).orElseThrow();
      assertThat(taskWhileNewRuns.getAttemptCount()).isEqualTo(2);
      assertThat(taskWhileNewRuns.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
      assertThat(taskWhileNewRuns.getLastError()).isNull();
      assertThat(resumeWhileNewRuns.getStatus()).isEqualTo(ResumeStatus.ANALYZING);
      assertThat(resumeWhileNewRuns.getSkillsSnapshot()).isNull();
      assertThat(resumeWhileNewRuns.getFailureReason()).isNull();

      releaseNew.countDown();
      assertThat(newOwner.get(10, TimeUnit.SECONDS))
          .isEqualTo(ResumeAnalysisHandler.Outcome.TERMINAL);
      assertThat(queryService.get(work.resume().getId()).profile()).isEqualTo(newerProfile);
      assertThat(taskRepository.findById(work.task().getId()).orElseThrow().getAttemptCount())
          .isEqualTo(2);
    } finally {
      releaseOld.countDown();
      releaseNew.countDown();
      executor.shutdownNow();
    }
  }

  private Work pendingWork() {
    ResumeEntity resume = resumeRepository.saveAndFlush(ResumeEntity.pending(1L,
        "candidate.txt",
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""),
        "Built a Payments API using Java, Spring Boot and MySQL."));
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        AsyncTaskType.RESUME_ANALYSIS,
        "resume:" + resume.getId(),
        "{\"resumeId\":" + resume.getId() + "}");
    task.setTaskId(UUID.randomUUID());
    return new Work(resume, taskRepository.saveAndFlush(task));
  }

  private ResumeProfile validProfile() {
    return new ResumeProfile(
        "Java backend engineer",
        List.of("Java", "Spring Boot", "MySQL"),
        List.of(new ResumeProfile.ProjectEvidence(
            "Payments API", "Implemented transaction APIs", List.of("Spring Boot"))),
        List.of("Backend development"),
        List.of("Scale is not stated"));
  }

  private record Work(ResumeEntity resume, AsyncTaskEntity task) {}

  private enum OldAttemptResult {
    SUCCESS,
    INVALID_OUTPUT,
    RUNTIME_FAILURE
  }
}
