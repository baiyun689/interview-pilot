package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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

import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@Testcontainers
class SubmitAnswerConcurrencyIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_answer_race");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean private RedissonClient redissonClient;
  @MockitoBean private AnswerEvaluator answerEvaluator;
  @MockitoBean private QuestionGenerator questionGenerator;
  @Autowired private InterviewTurnClaimer claimer;
  @Autowired private AnswerAttemptRepository attempts;
  @Autowired private SubmitAnswerService submitService;
  @Autowired private InterviewQueryService queryService;
  @Autowired private ResumeRepository resumes;
  @Autowired private JobProfileRepository jobs;
  @Autowired private InterviewSessionRepository sessions;
  @Autowired private InterviewTurnRepository turns;
  @Autowired private AsyncTaskRepository tasks;
  @Autowired private ObjectMapper objectMapper;

  private static final CurrentUser LEGACY_USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");

  private ExecutorService executor;
  private UUID sessionId;

  @BeforeEach
  void setUp() throws Exception {
    tasks.deleteAll();
    attempts.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    executor = Executors.newFixedThreadPool(2);
    reset(answerEvaluator, questionGenerator);

    ResumeEntity resume = ResumeEntity.pending(1L,
        "candidate.txt", UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""), "Java services");
    resume.setStatus(ResumeStatus.READY);
    resume.setSkillsSnapshot(objectMapper.writeValueAsString(new ResumeProfile(
        "Java engineer", List.of("Java"), List.of(), List.of(), List.of())));
    resume = resumes.saveAndFlush(resume);
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        "Backend", "Java and Spring", "{\"competencies\":[\"Java\",\"Spring\"],\"preferredSkills\":[]}"));
    InterviewSessionEntity session = InterviewSessionEntity.create(
        resume.getId(), job.getId(), Difficulty.MEDIUM, 5, "deepseek", "deepseek-chat",
        objectMapper.writeValueAsString(new InterviewPlan(List.of("Java", "Spring"), 5)));
    session.start();
    session = sessions.saveAndFlush(session);
    turns.saveAndFlush(InterviewTurnEntity.firstAsked(
        session.getId(), Difficulty.MEDIUM, "Explain optimistic locking.", "Java"));
    sessionId = session.getSessionId();
  }

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  void sameRequestIdConcurrentlyProducesOneOwnerAndOneReplayableProcessingClaim() throws Exception {
    UUID requestId = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    Future<InterviewTurnClaim> first = submit(start, requestId, "Use a version column.");
    Future<InterviewTurnClaim> second = submit(start, requestId, "Use a version column.");
    start.countDown();

    List<InterviewTurnClaim> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

    assertThat(results).filteredOn(InterviewTurnClaim::owner).hasSize(1);
    assertThat(results).extracting(InterviewTurnClaim::state)
        .containsExactlyInAnyOrder(InterviewTurnClaim.State.OWNER, InterviewTurnClaim.State.PROCESSING);
    assertThat(turns.findByRequestId(requestId)).isPresent();
    assertThat(attempts.findByRequestId(requestId)).isPresent();
    assertThat(turns.count()).isEqualTo(1);
  }

  @Test
  void differentRequestIdsConcurrentlyAllowOnlyOneOwner() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Future<InterviewTurnClaim> first = submit(start, UUID.randomUUID(), "First answer");
    Future<InterviewTurnClaim> second = submit(start, UUID.randomUUID(), "Second answer");
    start.countDown();

    int owners = 0;
    int conflicts = 0;
    for (Future<InterviewTurnClaim> future : List.of(first, second)) {
      try {
        if (future.get(15, TimeUnit.SECONDS).owner()) {
          owners++;
        }
      } catch (ExecutionException exception) {
        assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
        conflicts++;
      }
    }
    assertThat(owners).isEqualTo(1);
    assertThat(conflicts).isEqualTo(1);
    assertThat(turns.count()).isEqualTo(1);
  }

  @Test
  void oneRequestIdCannotBeReusedForAnotherAnswerOrSession() throws Exception {
    UUID requestId = UUID.randomUUID();
    claimer.claim(LEGACY_USER, sessionId, requestId, "Original answer");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> claimer.claim(LEGACY_USER, sessionId, requestId, "Different answer"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("The requestId was already used for another answer");

    var original = sessions.findBySessionId(sessionId).orElseThrow();
    InterviewSessionEntity other = InterviewSessionEntity.create(
        original.getResumeId(), original.getJobProfileId(), Difficulty.MEDIUM, 5,
        "deepseek", "deepseek-chat",
        objectMapper.writeValueAsString(new InterviewPlan(List.of("Java", "Spring"), 5)));
    other.start();
    other = sessions.saveAndFlush(other);
    turns.saveAndFlush(InterviewTurnEntity.firstAsked(
        other.getId(), Difficulty.MEDIUM, "Explain Java.", "Java"));

    UUID otherSessionId = other.getSessionId();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> claimer.claim(LEGACY_USER, otherSessionId, requestId, "Original answer"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("The requestId was already used for another answer");
  }

  @Test
  void sameRequestIdRunsOneEvaluatorOutsideTransactionAndPersistsOneNextTurn() throws Exception {
    stubSuccessfulEvaluation();
    UUID requestId = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    Future<AnswerProcessingResult> first = submitService(start, requestId, "Use a version column.");
    Future<AnswerProcessingResult> second = submitService(start, requestId, "Use a version column.");
    start.countDown();

    AnswerProcessingResult firstResult = first.get(15, TimeUnit.SECONDS);
    AnswerProcessingResult secondResult = second.get(15, TimeUnit.SECONDS);

    verify(answerEvaluator, times(1)).evaluate(any());
    verify(questionGenerator, times(1)).nextQuestion(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), any(), any(), any());
    assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
        .containsExactlyInAnyOrder(false, true);
    assertThat(firstResult.evaluation()).isEqualTo(secondResult.evaluation());
    var persisted = sessions.findBySessionId(sessionId).orElseThrow();
    assertThat(turns.findAllBySessionIdOrderByTurnNo(persisted.getId())).hasSize(2);
    assertThat(turns.findAllBySessionIdOrderByTurnNo(persisted.getId()).get(1).getRequestId()).isNull();
    var reconnected = queryService.get(LEGACY_USER, sessionId).turns().get(0);
    assertThat(reconnected.answer()).isEqualTo("Use a version column.");
    assertThat(reconnected.feedback()).isEqualTo("Good concurrency explanation");
    assertThat(reconnected.score()).isEqualTo(82);
    assertThat(reconnected.evidence()).containsExactly("Uses a version column");
    assertThat(reconnected.decision().targetCompetency()).isEqualTo("Spring");
    assertThat(reconnected.nextDifficulty()).isEqualTo(Difficulty.MEDIUM);
    assertThat(reconnected.answeredAt()).isNotNull();
  }

  @Test
  void processingDuplicateWaitsBeyondTheOldFiveSecondDeadlineAndReplaysResult() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    when(answerEvaluator.evaluate(any())).thenAnswer(invocation -> {
      entered.countDown();
      Thread.sleep(5_300);
      return successfulEvaluation();
    });
    when(questionGenerator.nextQuestion(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), any(), any(), any()))
        .thenReturn(new GeneratedQuestion("Explain Spring transactions.", "Spring"));
    UUID requestId = UUID.randomUUID();
    Future<AnswerProcessingResult> owner = executor.submit(() -> submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(requestId, "Slow answer")));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    Future<AnswerProcessingResult> duplicate = executor.submit(() -> submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(requestId, "Slow answer")));

    AnswerProcessingResult ownerResult = owner.get(15, TimeUnit.SECONDS);
    AnswerProcessingResult duplicateResult = duplicate.get(15, TimeUnit.SECONDS);
    assertThat(ownerResult.replayed()).isFalse();
    assertThat(duplicateResult.replayed()).isTrue();
    assertThat(duplicateResult.evaluation()).isEqualTo(ownerResult.evaluation());
    verify(answerEvaluator, times(1)).evaluate(any());
  }

  @Test
  void staleAiCompletionCannotOverwriteANewerRetryOwner() throws Exception {
    CountDownLatch evaluatorEntered = new CountDownLatch(1);
    CountDownLatch releaseEvaluator = new CountDownLatch(1);
    when(answerEvaluator.evaluate(any())).thenAnswer(invocation -> {
      evaluatorEntered.countDown();
      releaseEvaluator.await(10, TimeUnit.SECONDS);
      return successfulEvaluation();
    });
    when(questionGenerator.nextQuestion(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), any(), any(), any()))
        .thenReturn(new GeneratedQuestion("Explain Spring transactions.", "Spring"));
    UUID oldRequest = UUID.randomUUID();
    Future<AnswerProcessingResult> old = executor.submit(() -> submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(oldRequest, "Old answer")));
    assertThat(evaluatorEntered.await(10, TimeUnit.SECONDS)).isTrue();

    var session = sessions.findBySessionId(sessionId).orElseThrow();
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), 1).orElseThrow();
    turn.setStatus(TurnStatus.FAILED);
    turn.setProcessingError("AI_PROCESSING_FAILED");
    turns.saveAndFlush(turn);
    UUID newRequest = UUID.randomUUID();
    InterviewTurnClaim newOwner = claimer.claim(LEGACY_USER, sessionId, newRequest, "New answer");
    assertThat(newOwner.owner()).isTrue();

    releaseEvaluator.countDown();
    try {
      old.get(15, TimeUnit.SECONDS);
      throw new AssertionError("stale result unexpectedly finalized");
    } catch (ExecutionException exception) {
      assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
    }
    var persisted = turns.findById(turn.getId()).orElseThrow();
    assertThat(persisted.getRequestId()).isEqualTo(newRequest);
    assertThat(persisted.getStatus()).isEqualTo(TurnStatus.PROCESSING);
    assertThat(persisted.getEvaluationSnapshot()).isNull();
  }

  @Test
  void aiFailureMarksOnlyItsClaimFailedAndANewRequestIdCanRetry() {
    when(answerEvaluator.evaluate(any())).thenThrow(new IllegalStateException("secret model detail"));
    UUID failedRequest = UUID.randomUUID();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> submitService.submit(LEGACY_USER,
            sessionId, new SubmitAnswerRequest(failedRequest, "First answer")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Answer processing failed; submit again with a new requestId");
    var session = sessions.findBySessionId(sessionId).orElseThrow();
    var failed = turns.findBySessionIdAndTurnNo(session.getId(), 1).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(TurnStatus.FAILED);
    assertThat(failed.getProcessingError()).isEqualTo("AI_PROCESSING_FAILED");
    assertThat(failed.getEvaluationSnapshot()).isNull();

    reset(answerEvaluator, questionGenerator);
    stubSuccessfulEvaluation();
    UUID retryRequest = UUID.randomUUID();
    AnswerProcessingResult retried = submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(retryRequest, "Corrected answer"));

    assertThat(retried.requestId()).isEqualTo(retryRequest);
    assertThat(turns.findById(failed.getId()).orElseThrow().getStatus())
        .isEqualTo(TurnStatus.COMPLETED);
    assertThat(turns.findById(failed.getId()).orElseThrow().getRequestId())
        .isEqualTo(retryRequest);
    assertThat(attempts.findByRequestId(failedRequest).orElseThrow().getStatus())
        .isEqualTo(AnswerAttemptStatus.FAILED);
    assertThat(attempts.findByRequestId(retryRequest).orElseThrow().getStatus())
        .isEqualTo(AnswerAttemptStatus.COMPLETED);
    assertThat(attempts.count()).isEqualTo(2);
  }

  @Test
  void finishSuggestionCannotSkipACompetencyScheduledByThePlan() {
    var session = sessions.findBySessionId(sessionId).orElseThrow();
    var finishSuggestion = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.INCREASE, "", "", "enough evidence", 0.9);
    when(answerEvaluator.evaluate(any())).thenReturn(new AnswerEvaluation(
        90, "Strong answer", List.of("version check"), List.of(), finishSuggestion));
    when(questionGenerator.nextQuestion(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), any(), any(), any()))
        .thenReturn(new GeneratedQuestion("Explain Spring transactions.", "Spring"));

    AnswerProcessingResult result = submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(UUID.randomUUID(), "Use optimistic versions"));

    assertThat(result.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(result.decision().targetCompetency()).isEqualTo("Spring");
    assertThat(result.decision().difficultyAdjustment()).isEqualTo(DifficultyAdjustment.KEEP);
    assertThat(result.nextQuestion()).isNotNull();
    assertThat(sessions.findBySessionId(sessionId).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.INTERVIEWING);
    assertThat(turns.findAllBySessionIdOrderByTurnNo(session.getId())).hasSize(2);
    assertThat(tasks.count()).isZero();
  }

  @Test
  void exhaustedTurnBudgetCreatesTheSameSingleReportTaskEvenWhenModelRequestsNextTopic() {
    var session = sessions.findBySessionId(sessionId).orElseThrow();
    turns.deleteAll();
    session.setCurrentTurnNo(5);
    sessions.saveAndFlush(session);
    turns.saveAndFlush(InterviewTurnEntity.nextAsked(
        session.getId(), 5, Difficulty.MEDIUM, "Final Java question", "Java"));
    var continueSuggestion = new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.INCREASE,
        "Spring", "transactions", "continue", 0.9);
    when(answerEvaluator.evaluate(any())).thenReturn(new AnswerEvaluation(
        80, "Enough", List.of("Evidence"), List.of("Depth"), continueSuggestion));

    AnswerProcessingResult result = submitService.submit(LEGACY_USER,
        sessionId, new SubmitAnswerRequest(UUID.randomUUID(), "Final answer"));

    assertThat(result.sessionStatus()).isEqualTo(SessionStatus.EVALUATING);
    assertThat(result.nextQuestion()).isNull();
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId)).isPresent();
    assertThat(tasks.count()).isEqualTo(1);
    verify(questionGenerator, times(0)).nextQuestion(any(), any(), any(), any());
  }

  @Test
  void invalidNestedStoredResumeProfileIsRejectedBeforeAiWithSanitizedFailure() {
    var session = sessions.findBySessionId(sessionId).orElseThrow();
    var resume = resumes.findById(session.getResumeId()).orElseThrow();
    resume.setSkillsSnapshot("""
        {"summary":"Java engineer","technicalSkills":["Java"],
         "projects":[{"name":" ","description":"x","technologies":["Java"]}],
         "strengths":[],"risks":[]}
        """);
    resumes.saveAndFlush(resume);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> submitService.submit(LEGACY_USER,
            sessionId, new SubmitAnswerRequest(UUID.randomUUID(), "answer")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Answer processing failed; submit again with a new requestId")
        .hasNoCause();
    verify(answerEvaluator, times(0)).evaluate(any());
  }

  private Future<InterviewTurnClaim> submit(
      CountDownLatch start, UUID requestId, String answer) {
    return executor.submit(() -> {
      start.await(10, TimeUnit.SECONDS);
      return claimer.claim(LEGACY_USER, sessionId, requestId, answer);
    });
  }

  private Future<AnswerProcessingResult> submitService(
      CountDownLatch start, UUID requestId, String answer) {
    return executor.submit(() -> {
      start.await(10, TimeUnit.SECONDS);
      return submitService.submit(LEGACY_USER,sessionId, new SubmitAnswerRequest(requestId, answer));
    });
  }

  private void stubSuccessfulEvaluation() {
    when(answerEvaluator.evaluate(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      AnswerEvaluationRequest request = invocation.getArgument(0);
      assertThat(request.providerId()).isEqualTo("deepseek");
      assertThat(request.modelName()).isEqualTo("deepseek-chat");
      return successfulEvaluation();
    });
    when(questionGenerator.nextQuestion(
        org.mockito.ArgumentMatchers.eq("deepseek"),
        org.mockito.ArgumentMatchers.eq("deepseek-chat"), any(), any(), any())).thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return new GeneratedQuestion("Explain Spring transactions.", "Spring");
        });
  }

  private AnswerEvaluation successfulEvaluation() {
    return new AnswerEvaluation(
        82, "Good concurrency explanation", List.of("Uses a version column"), List.of(),
        new InterviewDecision(
            NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP, "Kotlin", "out of plan",
            "model suggests an untrusted target", 0.9));
  }
}
