package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import interview.pilot.async.domain.*;
import interview.pilot.async.infrastructure.*;
import interview.pilot.async.messaging.*;
import interview.pilot.auth.infrastructure.*;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL transactions, with barriers at the external model boundary. */
@SpringBootTest(properties = {
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.interview.answer-recovery.initial-delay=1h",
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class QuestionPreparationFencingIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("preparation_fencing");
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }
  @MockitoBean RedissonClient redis;
  @MockitoBean TaskMessagePublisher publisher;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewQuestionCardRepository cards;
  @Autowired UserAccountRepository users;
  @Autowired PlatformTransactionManager transactionManager;

  private QuestionPreparationHandler handler;
  private QuestionSkeletonGenerator generator;
  private TaskMessage message;
  private InterviewSessionEntity session;

  @BeforeEach void setUp() {
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
    generator = mock(QuestionSkeletonGenerator.class);
    var retrieval = mock(QuestionRagRetriever.class);
    when(retrieval.retrieve(any(), any())).thenReturn(RagContextSnapshot.notConfigured());
    var rubrics = mock(RubricGenerator.class);
    when(rubrics.generate(any(), any(), any())).thenReturn(new PreparedQuestionDeck(List.of(
        new PreparedQuestionDeck.PreparedQuestion(InterviewPhase.FUNDAMENTALS, 1, "Idempotency",
            "How do you prevent duplicate writes?", List.of("unique key"), "Idempotency", List.of("idempotency"),
            GroundingMode.GENERAL, List.of(), List.of(new RubricPoint("identity", "business key"),
                new RubricPoint("atomicity", "conditional update")), "What happens after a crash?"))));
    handler = new QuestionPreparationHandler(tasks, sessions, cards, generator, retrieval, rubrics,
        mock(FollowUpQuotaAllocator.class), mapper, transactionManager);
  }

  @Test void aNewCommittedAttemptFencesTheOldResultEvenWhileTheTaskIsStillPublished() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(generator.generate(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        return skeletons();
      }
      throw new IllegalStateException("second worker model failure");
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> handler.prepare(message));
      try {
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> handler.prepare(message))
            .isInstanceOfSatisfying(QuestionPreparationExecutionException.class,
                error -> assertThat(error.attemptGeneration()).isEqualTo(2));
        assertThat(handler.markInvalid(message, 1)).isFalse();
        assertThat(handler.markDead(message, 1)).isFalse();
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

  private List<QuestionSkeletonOutput.Skeleton> skeletons() {
    return List.of(new QuestionSkeletonOutput.Skeleton(InterviewPhase.FUNDAMENTALS, 1, "Idempotency",
        "How do you prevent duplicate writes?", List.of("unique key"), "Idempotency", List.of("idempotency"),
        "What happens after a crash?"));
  }
}
