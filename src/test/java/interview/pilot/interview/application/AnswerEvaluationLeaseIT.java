package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import interview.pilot.async.domain.*;
import interview.pilot.async.infrastructure.*;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.*;
import interview.pilot.async.application.AsyncTaskService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.infrastructure.*;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.databind.ObjectMapper;

/** Real database and broker, with model calls replaced by deterministic barriers/results. */
@SpringBootTest(properties = {
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.interview.answer-recovery.initial-delay=1h",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class AnswerEvaluationLeaseIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("answer_evaluation_lease")
      // Isolated test database only: permit a trigger that injects a final-write failure.
      .withCommand("--log-bin-trust-function-creators=1");
  @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));
  @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
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
  @MockitoBean AnswerEvaluator evaluator;
  @MockitoBean FixedReportGenerator reportGenerator;
  @Autowired AnswerEvaluationHandler handler;
  @Autowired FixedInterviewReportHandler reportHandler;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewTurnRepository turns;
  @Autowired InterviewQuestionCardRepository cards;
  @Autowired InterviewReportRepository reports;
  @Autowired UserAccountRepository users;
  @Autowired TaskMessagePublisher publisher;
  @Autowired RabbitAdmin rabbitAdmin;
  @Autowired RabbitTemplate rabbit;
  @Autowired JdbcTemplate jdbc;
  @Autowired AsyncTaskService taskService;
  @Autowired ObjectMapper mapper;
  private TaskMessage message;
  private InterviewSessionEntity session;
  private InterviewTurnEntity evaluatedTurn;

  @BeforeEach void setUp() {
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_QUEUE, false);
    rabbitAdmin.purgeQueue(RabbitTopologyConfig.ANSWER_EVALUATION_DLQ, false);
    for (int retry = 1; retry <= 3; retry++) {
      rabbitAdmin.purgeQueue(RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_QUEUE + ".retry." + retry, false);
    }
    tasks.deleteAll(); reports.deleteAll(); turns.deleteAll(); cards.deleteAll(); sessions.deleteAll(); users.deleteAll();
    var user = users.saveAndFlush(UserAccountEntity.register("evaluation@example.test", "hash", "Evaluation"));
    var brief = new InterviewBriefSnapshot(JobSourceType.CUSTOM, null, null, "Backend", "Java backend",
        null, null, Difficulty.MEDIUM, InterviewSize.QUICK, "test", "model", null, 1);
    session = InterviewSessionEntity.preparing(user.getId(), null, brief.difficulty(), brief.interviewSize(),
        brief.jobSourceType(), brief.jobTitle(), brief.providerId(), brief.modelName(), mapper.writeValueAsString(brief), null);
    session.preparationReady(); session.beginFixedInterview();
    session = sessions.saveAndFlush(session);
    var phases = List.of(InterviewPhase.SELF_INTRODUCTION, InterviewPhase.FUNDAMENTALS, InterviewPhase.FUNDAMENTALS,
        InterviewPhase.PROJECT_EXPERIENCE, InterviewPhase.PROJECT_EXPERIENCE, InterviewPhase.SCENARIO_TRADEOFF);
    var phaseSequences = new java.util.EnumMap<InterviewPhase, Integer>(InterviewPhase.class);
    for (int i = 0; i < phases.size(); i++) {
      var phase = phases.get(i);
      var rag = RagContextSnapshot.notConfigured();
      int sequence = phaseSequences.merge(phase, 1, Integer::sum);
      var card = cards.saveAndFlush(InterviewQuestionCardEntity.create(session.getId(), phase, sequence,
          "Idempotency", "Explain idempotency", "[\"unique key\"]", GroundingMode.GENERAL, rag.status(),
          mapper.writeValueAsString(rag), "[]", 0, null));
      var turn = InterviewTurnEntity.asked(session.getId(), i + 1, phase,
          i == 0 ? QuestionType.SELF_INTRODUCTION : QuestionType.MAIN, card.getId(), card.getQuestionText());
      turn.beginAnswer(UUID.randomUUID(), "Use a durable unique business key", InputMode.TEXT);
      turn.completeAnswer();
      if (i == 0) turn.skipEvaluation();
      if (i == 1) turn.markEvaluationPending();
      turn = turns.saveAndFlush(turn);
      if (i == 1) evaluatedTurn = turn;
      if (i > 0) session.advanceTo(i + 1, QuestionType.MAIN);
    }
    session = sessions.saveAndFlush(session);
    var task = tasks.saveAndFlush(AsyncTaskEntity.pending(user.getId(), AsyncTaskType.ANSWER_EVALUATION,
        "answer-eval:" + session.getSessionId() + ":2", "{}"));
    message = new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey());
    doThrow(new IllegalStateException("Redis unavailable")).when(claims).acquire(anyString(), any());
    doThrow(new IllegalStateException("Redis unavailable")).when(claims).clearTerminal(anyString());
  }

  @AfterEach void neverUsesRedisClaims() { verifyNoInteractions(claims); }

  @Test void duplicateDeliveryCannotEvaluateWhileTheDatabaseOwnerIsActive() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(evaluator.evaluate(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
      }
      return evaluation(80);
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> handler.evaluate(message));
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        var duplicate = handler.evaluate(message);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(duplicate).isEqualTo(AnswerEvaluationHandler.Outcome.BUSY);
      } finally { release.countDown(); }
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(AnswerEvaluationHandler.Outcome.TERMINAL);
    }
  }

  @Test void simultaneousClaimsInvokeOnlyOneEvaluation() throws Exception {
    int workers = 6;
    var ready = new CountDownLatch(workers);
    var start = new CountDownLatch(1);
    var losersFinished = new CountDownLatch(workers - 1);
    var modelStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(evaluator.evaluate(any())).thenAnswer(invocation -> {
      calls.incrementAndGet();
      modelStarted.countDown();
      assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
      return evaluation(80);
    });
    try (var executor = Executors.newFixedThreadPool(workers)) {
      var results = new ArrayList<Future<AnswerEvaluationHandler.Outcome>>();
      for (int i = 0; i < workers; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
          try { return handler.evaluate(message); }
          finally { losersFinished.countDown(); }
        }));
      }
      try {
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(losersFinished.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(modelStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
      } finally { start.countDown(); release.countDown(); }
      var outcomes = new ArrayList<AnswerEvaluationHandler.Outcome>();
      for (var result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
      assertThat(outcomes).containsOnly(AnswerEvaluationHandler.Outcome.TERMINAL, AnswerEvaluationHandler.Outcome.BUSY);
      assertThat(outcomes.stream().filter(value -> value == AnswerEvaluationHandler.Outcome.TERMINAL).count()).isEqualTo(1);
    }
    assertThat(currentTask().getAttemptCount()).isEqualTo(1);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(currentTurn().getEvalStatus()).isEqualTo(EvalStatus.GENERAL_FALLBACK);
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void expiredOwnerCannotOverwriteTheNewScoreOrReportFailure(boolean oldSucceeds) throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(evaluator.evaluate(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        if (!oldSucceeds) throw new IllegalStateException("old model failure");
        return evaluation(10);
      }
      return evaluation(90);
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old = executor.submit(() -> handler.evaluate(message));
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        String oldToken = currentTask().getExecutionToken();
        expireLease();
        assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.TERMINAL);
        assertThat(handler.markDead(message, 1, oldToken, evaluatedTurn.getRequestId())).isFalse();
        handler.releaseForRetry(message, 1, oldToken);
      } finally { release.countDown(); }
      assertThat(old.get(15, TimeUnit.SECONDS)).isEqualTo(AnswerEvaluationHandler.Outcome.STALE);
    }
    assertThat(mapper.readValue(currentTurn().getAnswerEvaluation(), AnswerEvaluation.class).score()).isEqualTo(90);
    assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(currentTask().getAttemptCount()).isEqualTo(2);
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void replacedAnswerRejectsBothOldScoreAndOldFailure(boolean oldSucceeds) throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(evaluator.evaluate(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        if (!oldSucceeds) throw new IllegalStateException("old model failure");
        return evaluation(10);
      }
      assertThat(((AnswerEvaluationInput) invocation.getArgument(0)).answer()).isEqualTo("new answer");
      return evaluation(90);
    });
    UUID newRequest = UUID.randomUUID();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old = executor.submit(() -> handler.evaluate(message));
      String oldToken;
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        oldToken = currentTask().getExecutionToken();
        // Fault injection: even a changed answer identity must not accept an old execution.
        jdbc.update("update interview_turn set request_id=?, answer_text='new answer', version=version+1 where id=?",
            newRequest.toString(), evaluatedTurn.getId());
      } finally { release.countDown(); }
      assertThat(old.get(15, TimeUnit.SECONDS)).isEqualTo(AnswerEvaluationHandler.Outcome.STALE);
      assertThat(handler.markDead(message, 1, oldToken, evaluatedTurn.getRequestId())).isFalse();
    }
    assertThat(currentTurn().getEvalStatus()).isEqualTo(EvalStatus.PENDING);
    assertThat(currentTurn().getAnswerEvaluation()).isNull();
    assertThat(currentTurn().getRequestId()).isEqualTo(newRequest);
    expireLease();
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.TERMINAL);
    assertThat(mapper.readValue(currentTurn().getAnswerEvaluation(), AnswerEvaluation.class).score()).isEqualTo(90);
  }

  @Test void wrongTokenCannotReleaseOrFailTheCurrentOwner() {
    when(evaluator.evaluate(any())).thenThrow(new IllegalStateException("model failed"));
    assertThatThrownBy(() -> handler.evaluate(message)).isInstanceOf(AnswerEvaluationRetryableException.class);
    String token = currentTask().getExecutionToken();
    handler.releaseForRetry(message, 1, "wrong-token");
    assertThat(handler.markDead(message, 1, "wrong-token", evaluatedTurn.getRequestId())).isFalse();
    assertThat(currentTask().getExecutionToken()).isEqualTo(token);
    assertThat(currentTurn().getEvalStatus()).isEqualTo(EvalStatus.PENDING);
  }

  @Test void finalTaskWriteFailureRollsBackTheEvaluationToo() {
    when(evaluator.evaluate(any())).thenReturn(evaluation(80));
    jdbc.execute("CREATE TRIGGER reject_eval_completion BEFORE UPDATE ON async_task FOR EACH ROW "
        + "BEGIN IF NEW.task_type='ANSWER_EVALUATION' AND NEW.status='COMPLETED' THEN "
        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected completion failure'; END IF; END");
    try {
      assertThatThrownBy(() -> handler.evaluate(message)).isInstanceOfSatisfying(
          AnswerEvaluationRetryableException.class, error -> {
            assertThat(error.attemptGeneration()).isEqualTo(1);
            assertThat(error.requestId()).isEqualTo(evaluatedTurn.getRequestId());
            assertThat(error.executionToken()).isEqualTo(currentTask().getExecutionToken());
          });
      assertThat(currentTurn().getEvalStatus()).isEqualTo(EvalStatus.PENDING);
      assertThat(currentTurn().getAnswerEvaluation()).isNull();
      assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    } finally { jdbc.execute("DROP TRIGGER reject_eval_completion"); }
    var failed = currentTask();
    handler.releaseForRetry(message, failed.getAttemptCount(), failed.getExecutionToken());
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.TERMINAL);
  }

  @Test void brokerRetryCompletesAndTerminalRedeliveryDoesNotEvaluateAgain() throws Exception {
    when(evaluator.evaluate(any())).thenThrow(new IllegalStateException("temporary failure")).thenReturn(evaluation(80));
    publisher.publish(message);
    await(() -> currentTask().getStatus() == AsyncTaskStatus.COMPLETED, 15);
    assertThat(currentTask().getAttemptCount()).isEqualTo(2);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(currentTask().getExecutionLeaseUntil()).isNull();
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.STALE);
    verify(evaluator, times(2)).evaluate(any());
  }

  @Test void crashedOwnerDeliveryWaitsForExpiryAndThenRecovers() throws Exception {
    jdbc.update("update async_task set status='PUBLISHED', attempt_count=1, execution_token='crashed', "
        + "execution_lease_until=timestampadd(minute,2,current_timestamp(6)) where task_id=?", message.taskId().toString());
    when(evaluator.evaluate(any())).thenReturn(evaluation(80));
    publisher.publish(message);
    await(() -> rabbitAdmin.getQueueInfo(RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_QUEUE + ".retry.2")
        .getMessageCount() == 1, 5);
    verifyNoInteractions(evaluator);
    expireLease();
    await(() -> currentTask().getStatus() == AsyncTaskStatus.COMPLETED, 40);
    assertThat(currentTask().getAttemptCount()).isEqualTo(2);
  }

  @Test void deadLetterKeepsTheAnswerAndInterviewFlowAndAllowsReportFallback() throws Exception {
    jdbc.update("update async_task set attempt_count=3 where task_id=?", message.taskId().toString());
    when(evaluator.evaluate(any())).thenThrow(new IllegalStateException("model failed"));
    rabbit.convertAndSend(RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_EXCHANGE, "interview.answer-evaluation", message,
        source -> { source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3); return source; });
    await(() -> currentTask().getStatus() == AsyncTaskStatus.DEAD, 10);
    assertThat(rabbit.receiveAndConvert(RabbitTopologyConfig.ANSWER_EVALUATION_DLQ, 5000)).isEqualTo(message);
    assertThat(currentTask().getAttemptCount()).isEqualTo(4);
    assertThat(currentTask().getExecutionToken()).isNull();
    assertThat(currentTurn().getEvalStatus()).isEqualTo(EvalStatus.FAILED);
    assertThat(currentTurn().getStatus()).isEqualTo(TurnStatus.COMPLETED);
    assertThat(currentTurn().getAnswerText()).isEqualTo(evaluatedTurn.getAnswerText());
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.INTERVIEWING);
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.STALE);
    assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
    TaskMessage reportTask = prepareReport();
    when(reportGenerator.generate(anyString(), anyString(), any())).thenAnswer(invocation -> {
      var input = (FixedReportInput) invocation.getArgument(2);
      var evidence = input.completedTurns().stream().filter(value -> value.turnNo() == 2).findFirst().orElseThrow();
      assertThat(evidence.evaluation()).isNull();
      assertThat(evidence.answer()).isEqualTo(evaluatedTurn.getAnswerText());
      return report();
    });
    assertThat(reportHandler.handle(reportTask, 0)).isEqualTo(FixedInterviewReportHandler.Outcome.TERMINAL);
    assertThat(reports.findBySessionId(session.getId())).isPresent();
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
  }

  @Test void reportWaitsOnlyWithinItsBudgetAndLateEvaluationDoesNotRewriteIt() {
    TaskMessage reportTask = prepareReport();
    assertThatThrownBy(() -> reportHandler.handle(reportTask, 0)).isInstanceOfSatisfying(ReportGenerationRetryableException.class,
        failure -> reportHandler.releaseForRetry(reportTask, failure.attemptGeneration(), failure.executionToken()));
    verifyNoInteractions(reportGenerator);
    when(reportGenerator.generate(anyString(), anyString(), any())).thenAnswer(invocation -> {
      var input = (FixedReportInput) invocation.getArgument(2);
      assertThat(input.completedTurns().stream().filter(value -> value.turnNo() == 2).findFirst().orElseThrow().evaluation()).isNull();
      return report();
    });
    assertThat(reportHandler.handle(reportTask, 3)).isEqualTo(FixedInterviewReportHandler.Outcome.TERMINAL);
    String frozenReport = reports.findBySessionId(session.getId()).orElseThrow().getReportSnapshot();
    when(evaluator.evaluate(any())).thenReturn(evaluation(99));
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.TERMINAL);
    assertThat(reports.findBySessionId(session.getId()).orElseThrow().getReportSnapshot()).isEqualTo(frozenReport);
  }

  @Test void reportUsesTheCompletedEvaluationWithoutWaiting() {
    when(evaluator.evaluate(any())).thenReturn(evaluation(88));
    handler.evaluate(message);
    TaskMessage reportTask = prepareReport();
    when(reportGenerator.generate(anyString(), anyString(), any())).thenAnswer(invocation -> {
      var input = (FixedReportInput) invocation.getArgument(2);
      assertThat(input.completedTurns().stream().filter(value -> value.turnNo() == 2)
          .findFirst().orElseThrow().evaluation().score()).isEqualTo(88);
      return report();
    });
    assertThat(reportHandler.handle(reportTask, 0)).isEqualTo(FixedInterviewReportHandler.Outcome.TERMINAL);
  }

  @ParameterizedTest @EnumSource(value = AsyncTaskStatus.class, names = {"FAILED", "DEAD"})
  void terminalTaskIsNeverResurrected(AsyncTaskStatus state) {
    jdbc.update("update async_task set status=? where task_id=?", state.name(), message.taskId().toString());
    assertThat(handler.evaluate(message)).isEqualTo(AnswerEvaluationHandler.Outcome.STALE);
    assertThat(currentTask().getStatus()).isEqualTo(state);
    verifyNoInteractions(evaluator);
  }

  @Test void manualRetryRemainsUnsupportedWithoutCallingRedis() {
    jdbc.update("update async_task set status='DEAD' where task_id=?", message.taskId().toString());
    var user = users.findById(session.getUserAccountId()).orElseThrow();
    assertThatThrownBy(() -> taskService.retry(new CurrentUser(user.getId(), user.getUserId(), user.getEmail(), user.getDisplayName()),
        message.taskId(), UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE"));
    assertThat(currentTask().getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "invalid", "failure"})
  void reportTakeoverFencesEveryOldOutcome(String oldOutcome) throws Exception {
    TaskMessage reportTask = prepareReport();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(reportGenerator.generate(anyString(), anyString(), any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        if (oldOutcome.equals("invalid")) return null;
        if (oldOutcome.equals("failure")) throw new IllegalStateException("old provider failure");
      }
      return report();
    });
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var old = pool.submit(() -> reportHandler.handle(reportTask, 3));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var original = tasks.findByTaskId(reportTask.taskId()).orElseThrow();
        assertThat(reportHandler.handle(reportTask, 3)).isEqualTo(FixedInterviewReportHandler.Outcome.BUSY);
        assertThat(reportHandler.markDead(reportTask, original.getAttemptCount(), "wrong-token")).isFalse();
        jdbc.update("update async_task set execution_lease_until=current_timestamp(6)-interval 1 second where task_id=?", reportTask.taskId().toString());
        assertThat(reportHandler.handle(reportTask, 3)).isEqualTo(FixedInterviewReportHandler.Outcome.TERMINAL);
        reportHandler.releaseForRetry(reportTask, original.getAttemptCount(), original.getExecutionToken());
        assertThat(reportHandler.markDead(reportTask, original.getAttemptCount(), original.getExecutionToken())).isFalse();
        release.countDown();
        assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(FixedInterviewReportHandler.Outcome.STALE);
        assertThat(reports.findBySessionId(session.getId())).isPresent();
        assertThat(tasks.findByTaskId(reportTask.taskId()).orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(calls.get()).isEqualTo(2);
      } finally { release.countDown(); }
    }
  }

  @Test void reportCommitFailureRollsBackReportSessionAndTaskTogether() {
    TaskMessage reportTask = prepareReport();
    when(reportGenerator.generate(anyString(), anyString(), any())).thenReturn(report());
    jdbc.execute("create trigger reject_report_completion before update on async_task for each row begin "
        + "if NEW.task_type='INTERVIEW_EVALUATION' and NEW.status='COMPLETED' then "
        + "signal sqlstate '45000' set message_text='injected report commit failure'; end if; end");
    try {
      assertThatThrownBy(() -> reportHandler.handle(reportTask, 3))
          .isInstanceOf(ReportGenerationRetryableException.class);
      assertThat(reports.findBySessionId(session.getId())).isEmpty();
      assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.EVALUATING);
      var task = tasks.findByTaskId(reportTask.taskId()).orElseThrow();
      assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
      assertThat(task.getExecutionToken()).isNotNull();
      reportHandler.releaseForRetry(reportTask, task.getAttemptCount(), task.getExecutionToken());
    } finally { jdbc.execute("drop trigger reject_report_completion"); }
    assertThat(reportHandler.handle(reportTask, 3)).isEqualTo(FixedInterviewReportHandler.Outcome.TERMINAL);
  }

  private TaskMessage prepareReport() {
    var stored = sessions.findById(session.getId()).orElseThrow();
    stored.beginEvaluation();
    sessions.saveAndFlush(stored);
    var task = tasks.saveAndFlush(AsyncTaskEntity.pending(stored.getUserAccountId(), AsyncTaskType.INTERVIEW_EVALUATION,
        "interview:" + stored.getSessionId(), "{}"));
    return new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey());
  }

  private FixedInterviewReport report() {
    return new FixedInterviewReport(75, Map.of(InterviewPhase.SELF_INTRODUCTION, 75, InterviewPhase.FUNDAMENTALS, 75,
        InterviewPhase.PROJECT_EXPERIENCE, 75, InterviewPhase.SCENARIO_TRADEOFF, 75), List.of("Clear answers"),
        List.of("Add examples"), List.of(), List.of(), "Overall assessment", Map.of(
            InterviewPhase.FUNDAMENTALS, RagContextSnapshot.notConfigured().status().name(),
            InterviewPhase.PROJECT_EXPERIENCE, RagContextSnapshot.notConfigured().status().name(),
            InterviewPhase.SCENARIO_TRADEOFF, RagContextSnapshot.notConfigured().status().name()));
  }

  private void expireLease() {
    jdbc.update("update async_task set execution_lease_until=timestampadd(second,-1,current_timestamp(6)) where task_id=?",
        message.taskId().toString());
  }

  private void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("Timed out waiting for answer evaluation");
  }

  private AnswerEvaluation evaluation(int score) {
    return new AnswerEvaluation(score, List.of("unique key"), List.of(), List.of(), List.of(),
        GroundingMode.GENERAL, EvalStatus.GENERAL_FALLBACK, "test-model", Instant.now());
  }
  private AsyncTaskEntity currentTask() { return tasks.findByTaskId(message.taskId()).orElseThrow(); }
  private InterviewTurnEntity currentTurn() { return turns.findById(evaluatedTurn.getId()).orElseThrow(); }
}
