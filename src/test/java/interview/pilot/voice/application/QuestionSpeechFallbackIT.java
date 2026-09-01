package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;

/**
 * Question speech scheduling with TTS unconfigured: speech is a degradable playback
 * capability — a VOICE session whose TTS provider is not configured gets the
 * NOT_AVAILABLE view and never a row or task (plan §11 gate).
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/question-speech-fallback-it-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-fallback-it",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    // application.yml provides TTS defaults — blank them so ttsConfigured() is false.
    "app.voice.tts.provider=",
    "app.voice.tts.model=",
    "app.voice.tts.voice=",
    "app.voice.tts.timeout=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "spring.autoconfigure.exclude="
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class QuestionSpeechFallbackIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_speech_fallback");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean
  private RedissonClient redissonClient;

  @Autowired
  private QuestionSpeechModule module;

  @Autowired
  private QuestionSpeechRepository speeches;

  @Autowired
  private InterviewSessionRepository sessions;

  @Autowired
  private InterviewTurnRepository turns;

  @Autowired
  private InterviewQuestionCardRepository cards;

  @Autowired
  private AsyncTaskRepository tasks;

  @Autowired
  private UserAccountRepository users;

  private CurrentUser user;
  private UserAccountEntity account;

  @BeforeEach
  void setUp() {
    tasks.deleteAll();
    speeches.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    account = users.save(UserAccountEntity.register(
        "speech-fallback@example.com", "hash", "Speech Fallback"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
  }

  @Test
  void getOrScheduleReturnsNotAvailableForVoiceSessionsWithoutTtsConfiguration() {
    var session = seedSession();
    seedTurn(session, 1);

    var view = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(view.status()).isEqualTo(QuestionSpeechViewStatus.NOT_AVAILABLE);
    assertThat(view.speechId()).isNull();
    assertThat(view.mediaUrl()).isNull();
    assertThat(view.retryable()).isFalse();
    assertThat(view.safeError()).isNull();
    assertThat(speeches.count()).isZero();
    assertThat(tasks.findAll().stream()
        .filter(row -> row.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isZero();
  }

  private InterviewSessionEntity seedSession() {
    var session = sessions.save(InterviewSessionEntity.preparing(
        account.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen-plus",
        "{}", null, InterviewMode.VOICE,
        "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
            + "\"ttsProvider\":\"unconfigured\",\"ttsModel\":\"unconfigured\",\"voice\":\"server-default\","
            + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"));
    session.preparationReady();
    session.beginFixedInterview();
    return sessions.saveAndFlush(session);
  }

  private void seedTurn(InterviewSessionEntity session, int turnNo) {
    var card = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    turns.save(InterviewTurnEntity.asked(
        session.getId(), turnNo, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), "请自我介绍"));
  }
}
