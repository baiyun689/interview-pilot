package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessagePublisher;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.application.FollowUpGenerator;
import interview.pilot.interview.application.StartInterviewService;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.application.QuestionSpeechTaskCreator;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Turn-creation contract against MySQL (V21 schema): StartInterviewService creates the intro
 * turn with exactly one question_speech row and one synthesis task in the SAME transaction
 * (a rollback removes all three), a replayed start never duplicates them, and TEXT sessions
 * create nothing. TTS stays a degradable capability — the speech status never participates
 * in the interview state machine (verified in VoiceSynthesisListenerIT).
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/question-speech-turn-creation-it-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-turn-creation-it",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "app.async.voice-transcription-listener.auto-startup=false",
    "app.async.voice-synthesis-listener.auto-startup=false"
})
@Testcontainers
class QuestionSpeechTurnCreationIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_speech_turn_creation");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @MockitoBean
  private TaskMessagePublisher publisher;

  @MockitoBean
  private FollowUpGenerator followUps;

  @Autowired
  private StartInterviewService startInterview;

  @Autowired
  private QuestionSpeechTaskCreator questionSpeeches;

  @Autowired
  private InterviewSessionRepository sessions;

  @Autowired
  private InterviewTurnRepository turns;

  @Autowired
  private InterviewQuestionCardRepository cards;

  @Autowired
  private QuestionSpeechRepository speeches;

  @Autowired
  private AsyncTaskRepository tasks;

  @Autowired
  private UserAccountRepository users;

  @Autowired
  private ObjectMapper json;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private CurrentUser user;

  @BeforeEach
  void setUp() {
    tasks.deleteAll();
    speeches.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    var account = users.save(UserAccountEntity.register(
        "speech-turn-creation-it@example.com", "hash", "Speech Turn Creation"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
  }

  @Test
  void voiceSessionStartCreatesExactlyOneSpeechRowAndTaskAndReplaysNeverDuplicate() {
    var session = seedSession(InterviewMode.VOICE);
    seedCard(session);

    var start = startInterview.start(user, session.getSessionId());
    assertThat(start.idempotentReplay()).isFalse();

    var turn = turns.findBySessionIdAndTurnNo(session.getId(), 1).orElseThrow();
    var speech = speeches.findByTurnId(turn.getId()).orElseThrow();
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId())).isPresent();
    assertThat(speeches.count()).isEqualTo(1);
    assertThat(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(),
        session.getUserAccountId())).isPresent();

    // A repeated start (crash after commit, client retry) returns the persisted turn and
    // never creates a second speech row or task.
    var replay = startInterview.start(user, session.getSessionId());
    assertThat(replay.idempotentReplay()).isTrue();
    assertThat(speeches.count()).isEqualTo(1);
    assertThat(tasks.findAll().stream()
        .filter(task -> task.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isEqualTo(1);
  }

  @Test
  void aRolledBackStartLeavesNoTurnSpeechOrTaskBehind() {
    var session = seedSession(InterviewMode.VOICE);
    seedCard(session);
    var outer = new TransactionTemplate(transactionManager);

    // The speech row and its task are created in the SAME transaction as the turn: when the
    // outer transaction rolls back, all three disappear together.
    assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
      startInterview.start(user, session.getSessionId());
      throw new RuntimeException("boom");
    })).isInstanceOf(RuntimeException.class);

    assertThat(turns.count()).isZero();
    assertThat(speeches.count()).isZero();
    assertThat(tasks.findAll().stream()
        .filter(task -> task.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isZero();
  }

  @Test
  void textSessionsCreateNoSpeechRowsOrTasks() {
    var session = seedSession(InterviewMode.TEXT);
    seedCard(session);

    var start = startInterview.start(user, session.getSessionId());

    assertThat(start.idempotentReplay()).isFalse();
    assertThat(speeches.count()).isZero();
    assertThat(tasks.findAll().stream()
        .filter(task -> task.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isZero();
  }

  @Test
  void concurrentCreationForOneTurnYieldsExactlyOneSpeechRowAndTask() throws Exception {
    var session = seedSession(InterviewMode.VOICE);
    var card = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    var turn = turns.save(InterviewTurnEntity.asked(
        session.getId(), 1, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), "请自我介绍"));

    int threads = 4;
    var barrier = new java.util.concurrent.CyclicBarrier(threads);
    var ids = new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
    var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
          try {
            barrier.await();
            ids.add(questionSpeeches.createForTurn(session, turn));
          } catch (Throwable failure) {
            errors.add(failure);
          }
        });
      }
    } finally {
      executor.shutdown();
      assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    assertThat(errors).isEmpty();
    // uq_question_speech_turn backstop: every racer returns the one winning speech id.
    assertThat(ids.stream().distinct().count()).isEqualTo(1);
    assertThat(speeches.count()).isEqualTo(1);
    assertThat(tasks.findAll().stream()
        .filter(task -> task.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isEqualTo(1);
  }

  private InterviewSessionEntity seedSession(InterviewMode mode) {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java 后端工程师",
        "负责 Spring Boot 微服务开发，熟悉 MySQL 与 Redis",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);
    var session = sessions.save(InterviewSessionEntity.preparing(
        user.databaseId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端工程师", "dashscope", "qwen",
        json.writeValueAsString(brief), null, mode,
        "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
            + "\"ttsProvider\":\"dashscope\",\"ttsModel\":\"cosyvoice-v3-flash\",\"voice\":\"longanyang\","
            + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"));
    session.preparationReady();
    return sessions.saveAndFlush(session);
  }

  private void seedCard(InterviewSessionEntity session) {
    cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
  }
}
