package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@Testcontainers
class CreateInterviewPersistenceIT {
  private static final CurrentUser LEGACY_USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_creation");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean private RedissonClient redissonClient;
  @MockitoBean private AiProviderService providers;
  @MockitoBean private JobProfileExtractor extractor;
  @MockitoBean private InterviewPlanner planner;
  @MockitoBean private QuestionGenerator questions;

  @Autowired private CreateInterviewService service;
  @Autowired private InterviewQueryService queryService;
  @Autowired private ResumeRepository resumes;
  @Autowired private JobProfileRepository jobs;
  @Autowired private InterviewSessionRepository sessions;
  @Autowired private InterviewTurnRepository turns;

  private Long resumeId;
  private ResumeProfile profile;
  private JobRequirements requirements;
  private InterviewPlan plan;

  @BeforeEach
  void setUp() throws Exception {
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    profile = new ResumeProfile(
        "Java engineer", List.of("Java"), List.of(), List.of("Reliable APIs"), List.of());
    requirements = new JobRequirements(List.of("Java", "Spring"), List.of("MySQL"));
    plan = new InterviewPlan(List.of("Java", "Spring"), 8);
    ResumeEntity resume = ResumeEntity.pending(1L,
        "candidate.txt", UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""),
        "Built Java services");
    resume.setStatus(ResumeStatus.READY);
    resume.setSkillsSnapshot(new tools.jackson.databind.ObjectMapper().writeValueAsString(profile));
    resumeId = resumes.saveAndFlush(resume).getId();
  }

  @Test
  void aiRunsOutsideTransactionsThenOneShortTransactionPersistsExactlyOneAskedTurn() {
    stubSuccessfulAi("deepseek", "deepseek-chat");

    var created = service.create(LEGACY_USER,request("deepseek"));

    var session = sessions.findBySessionId(created.sessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(SessionStatus.INTERVIEWING);
    assertThat(session.getCurrentTurnNo()).isEqualTo(1);
    assertThat(session.getProviderId()).isEqualTo("deepseek");
    assertThat(session.getModelName()).isEqualTo("deepseek-chat");
    assertThat(session.getTotalTurnBudget()).isEqualTo(8);
    assertThat(turns.findAllBySessionIdOrderByTurnNo(session.getId()))
        .singleElement()
        .satisfies(turn -> {
          assertThat(turn.getTurnNo()).isEqualTo(1);
          assertThat(turn.getRequestId()).isNull();
          assertThat(turn.getStatus()).isEqualTo(TurnStatus.ASKED);
          assertThat(turn.getTargetCompetency()).isEqualTo("Java");
        });
  }

  @Test
  void providerDefaultChangeDoesNotChangeThePersistedSessionSnapshot() {
    stubSuccessfulAi("qwen", "qwen-plus");
    var created = service.create(LEGACY_USER,request(null));
    when(providers.resolveEnabled(null)).thenReturn(
        new AiProviderDescriptor("deepseek", "DeepSeek", "deepseek-chat", true, true));

    var loaded = queryService.get(LEGACY_USER,created.sessionId());

    assertThat(loaded.providerId()).isEqualTo("qwen");
    assertThat(loaded.modelName()).isEqualTo("qwen-plus");
  }

  @Test
  void aiFailureLeavesNoPartialJobSessionOrTurn() {
    when(providers.resolveEnabled("deepseek")).thenReturn(
        new AiProviderDescriptor("deepseek", "DeepSeek", "deepseek-chat", true, false));
    when(extractor.extract(org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("Build reliable Java services"),
        org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("provider failed"));

    assertThatThrownBy(() -> service.create(LEGACY_USER,request("deepseek")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jobs.count()).isZero();
    assertThat(sessions.count()).isZero();
    assertThat(turns.count()).isZero();
  }

  @Test
  void transactionRecheckRejectsResumeThatBecameNotReadyBeforePersistence() {
    stubSuccessfulAi("deepseek", "deepseek-chat");
    when(questions.firstQuestion(org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq(plan), org.mockito.ArgumentMatchers.eq(profile),
        org.mockito.ArgumentMatchers.eq(requirements), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
      ResumeEntity changed = resumes.findById(resumeId).orElseThrow();
      changed.setStatus(ResumeStatus.FAILED);
      resumes.saveAndFlush(changed);
      return new GeneratedQuestion("Explain optimistic locking.", "Java");
    });

    assertThatThrownBy(() -> service.create(LEGACY_USER,request("deepseek")))
        .hasMessage("Resume analysis is not ready");
    assertThat(jobs.count()).isZero();
    assertThat(sessions.count()).isZero();
    assertThat(turns.count()).isZero();
  }

  @Test
  void concurrentCreationsForOneReadyResumeProduceIndependentCompleteSessions() throws Exception {
    stubSuccessfulAi("deepseek", "deepseek-chat");

    CompletableFuture<UUID> first = CompletableFuture.supplyAsync(
        () -> service.create(LEGACY_USER,request("deepseek")).sessionId());
    CompletableFuture<UUID> second = CompletableFuture.supplyAsync(
        () -> service.create(LEGACY_USER,request("deepseek")).sessionId());
    UUID firstId = first.get(15, TimeUnit.SECONDS);
    UUID secondId = second.get(15, TimeUnit.SECONDS);

    assertThat(firstId).isNotEqualTo(secondId);
    assertThat(jobs.count()).isEqualTo(2);
    assertThat(sessions.count()).isEqualTo(2);
    assertThat(turns.count()).isEqualTo(2);
    assertThat(sessions.findAll()).allSatisfy(session ->
        assertThat(turns.findAllBySessionIdOrderByTurnNo(session.getId()))
            .singleElement()
            .extracting("status")
            .isEqualTo(TurnStatus.ASKED));
  }

  private void stubSuccessfulAi(String providerId, String model) {
    when(providers.resolveEnabled(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(
        new AiProviderDescriptor(providerId, providerId, model, true, true));
    when(extractor.extract(org.mockito.ArgumentMatchers.eq(providerId),
        org.mockito.ArgumentMatchers.eq("Build reliable Java services"),
        org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return requirements;
    });
    when(planner.plan(org.mockito.ArgumentMatchers.eq(providerId),
        org.mockito.ArgumentMatchers.eq(profile), org.mockito.ArgumentMatchers.eq(requirements),
        org.mockito.ArgumentMatchers.eq(Difficulty.MEDIUM), org.mockito.ArgumentMatchers.eq(8),
        org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return plan;
    });
    when(questions.firstQuestion(org.mockito.ArgumentMatchers.eq(providerId),
        org.mockito.ArgumentMatchers.eq(plan), org.mockito.ArgumentMatchers.eq(profile),
        org.mockito.ArgumentMatchers.eq(requirements), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new GeneratedQuestion("Explain optimistic locking.", "Java");
    });
  }

  private CreateInterviewRequest request(String providerId) {
    return new CreateInterviewRequest(
        resumeId, "Backend Engineer", "Build reliable Java services",
        Difficulty.MEDIUM, 8, providerId);
  }
}
