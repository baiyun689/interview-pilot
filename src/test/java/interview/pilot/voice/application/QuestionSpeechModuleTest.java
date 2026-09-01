package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpRange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.PendingTaskDispatcher;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskMessagePublisher;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.exception.BusinessException;
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
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceRangeNotSatisfiableException;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * Question speech module contract against MySQL (V21 schema, real repositories) with the real
 * {@code FileSystemVoiceMediaStore}, a mocked Redis claim and the real synthesis handler
 * driven through the {@link FakeSpeechSynthesizer} seam: getOrSchedule views, the one-row
 * creation path, the FAILED → PENDING retry with speech/task epoch lockstep, the stale
 * old-generation fence, and the protected ranged media reads.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/question-speech-module-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-module-test",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "app.async.voice-synthesis-listener.auto-startup=false",
    "spring.autoconfigure.exclude="
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class QuestionSpeechModuleTest {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_speech_module");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean
  private RedissonClient redissonClient;

  @MockitoBean
  private TaskMessagePublisher publisher;

  @MockitoBean
  private ProcessingClaim claims;

  @MockitoBean
  private AudioProbe probe;

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

  @Autowired
  private VoiceSynthesisHandler handler;

  @Autowired
  private FakeSpeechSynthesizer fakeSynthesizer;

  @Autowired
  private VoiceMediaStore mediaStore;

  @Autowired
  private PendingTaskDispatcher dispatcher;

  private CurrentUser user;
  private UserAccountEntity account;
  private InterviewSessionEntity session;
  private InterviewTurnEntity turn;

  @BeforeEach
  void setUp() {
    when(claims.clearTerminal(anyString())).thenReturn(ProcessingClaim.ClearResult.CLEARED);
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/mpeg", Duration.ofSeconds(3)));
    fakeSynthesizer.reset();
    tasks.deleteAll();
    speeches.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    account = users.save(UserAccountEntity.register(
        "speech-module@example.com", "hash", "Speech Module"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedSession(InterviewMode.VOICE, SessionStatus.INTERVIEWING);
    turn = seedTurn(session, 1);
  }

  // ---------------------------------------------------------------- getOrSchedule views

  @Test
  void getOrScheduleReturnsTheExistingRowViewInPendingAndSynthesizing() {
    var scheduled = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(scheduled.status()).isEqualTo(QuestionSpeechViewStatus.PENDING);
    assertThat(scheduled.speechId()).isNotNull();
    assertThat(scheduled.mediaUrl()).isNull();
    assertThat(scheduled.retryable()).isFalse();
    assertThat(scheduled.safeError()).isNull();

    var speech = speeches.findByTurnId(turn.getId()).orElseThrow();
    speech.moveTo(QuestionSpeechStatus.SYNTHESIZING);
    speeches.saveAndFlush(speech);

    var synthesizing = module.getOrSchedule(user, session.getSessionId(), 1);
    assertThat(synthesizing.status()).isEqualTo(QuestionSpeechViewStatus.SYNTHESIZING);
    assertThat(synthesizing.speechId()).isEqualTo(scheduled.speechId());
    assertThat(synthesizing.mediaUrl()).isNull();
    assertThat(speeches.count()).isEqualTo(1);
  }

  @Test
  void getOrScheduleReturnsTheReadyViewWithTheMediaUrl() {
    var speech = synthesizeReady("0123456789");

    var view = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(view.status()).isEqualTo(QuestionSpeechViewStatus.READY);
    assertThat(view.speechId()).isEqualTo(speech.getSpeechId());
    assertThat(view.mediaUrl())
        .isEqualTo("/api/interviews/" + session.getSessionId() + "/speech/"
            + speech.getSpeechId() + "/media");
    assertThat(view.retryable()).isFalse();
    assertThat(view.safeError()).isNull();
  }

  @Test
  void getOrScheduleReturnsTheFailedViewWithRetryableAndSafeError() {
    var speech = synthesizeFailed();

    var view = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(view.status()).isEqualTo(QuestionSpeechViewStatus.FAILED);
    assertThat(view.speechId()).isEqualTo(speech.getSpeechId());
    assertThat(view.mediaUrl()).isNull();
    assertThat(view.retryable()).isTrue();
    assertThat(view.safeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
  }

  // ---------------------------------------------------------------- getOrSchedule creation

  @Test
  void getOrScheduleCreatesTheMissingRowAndTaskAndPublishesTheSynthesisMessage() {
    var view = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(view.status()).isEqualTo(QuestionSpeechViewStatus.PENDING);
    var speech = speeches.findByTurnId(turn.getId()).orElseThrow();
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(speech.getUserAccountId()).isEqualTo(account.getId());
    assertThat(speech.getTextSha256())
        .isEqualTo(QuestionSpeechHashes.of(turn.getQuestionText()));
    var task = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId()).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isZero();
    assertThat(tasks.findAll().stream()
        .filter(row -> row.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isEqualTo(1);

    dispatcher.dispatchPendingTasks();
    verify(publisher).publish(new TaskMessage(
        task.getTaskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(), 0));
  }

  @Test
  void getOrScheduleReturnsNotAvailableForTextSessionsWithoutCreatingAnything() {
    var textSession = seedSession(InterviewMode.TEXT, SessionStatus.INTERVIEWING);
    seedTurn(textSession, 1);

    var view = module.getOrSchedule(user, textSession.getSessionId(), 1);

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

  @Test
  void getOrScheduleHidesForeignSessionsAndMissingTurnsAsNotFound() {
    assertThatThrownBy(() -> module.getOrSchedule(user, UUID.randomUUID(), 1))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND);
          assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });

    var other = users.save(UserAccountEntity.register(
        "speech-other@example.com", "hash", "Other"));
    var strangerSession = seedSession(InterviewMode.VOICE, SessionStatus.INTERVIEWING);
    seedTurn(strangerSession, 1);
    var stranger = new CurrentUser(
        other.getId(), other.getUserId(), other.getEmail(), other.getDisplayName());
    assertThatThrownBy(() -> module.getOrSchedule(stranger, strangerSession.getSessionId(), 1))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND));

    assertThatThrownBy(() -> module.getOrSchedule(user, session.getSessionId(), 99))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND));
    assertThat(speeches.count()).isZero();
  }

  // ---------------------------------------------------------------- retry

  @Test
  void retryFromFailedFencesSpeechAndTaskEpochsAndRepublishes() {
    var speech = synthesizeFailed();

    module.retry(user, session.getSessionId(), speech.getSpeechId());

    var retried = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(retried.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(retried.getExecutionEpoch()).isEqualTo(1);
    // The listener's terminal claim is cleared so the retried message can acquire it
    // (the claim key IS the voice bizKey).
    verify(claims).clearTerminal(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId());
    var task = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId()).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isEqualTo(1);
    assertThat(task.getLastPublishedAt()).isNull();
    assertThat(tasks.findAll().stream()
        .filter(row -> row.getTaskType() == AsyncTaskType.QUESTION_SPEECH_SYNTHESIS)
        .count()).isEqualTo(1);

    dispatcher.dispatchPendingTasks();
    verify(publisher).publish(new TaskMessage(
        task.getTaskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(), 1));
  }

  @Test
  void retryFromNonFailedStatesIsRejectedWithTheNotReadyCode() {
    var scheduled = module.getOrSchedule(user, session.getSessionId(), 1);
    assertThatThrownBy(() ->
        module.retry(user, session.getSessionId(), scheduled.speechId()))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });

    var synthesizing = speeches.findBySpeechId(scheduled.speechId()).orElseThrow();
    synthesizing.moveTo(QuestionSpeechStatus.SYNTHESIZING);
    speeches.saveAndFlush(synthesizing);
    assertThatThrownBy(() ->
        module.retry(user, session.getSessionId(), scheduled.speechId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));

    var ready = synthesizeReady("0123456789");
    assertThatThrownBy(() ->
        module.retry(user, session.getSessionId(), ready.getSpeechId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));
  }

  @Test
  void retryWithAnActiveListenerClaimIsRejected() {
    var speech = synthesizeFailed();
    when(claims.clearTerminal(anyString()))
        .thenReturn(ProcessingClaim.ClearResult.ACTIVE);

    assertThatThrownBy(() ->
        module.retry(user, session.getSessionId(), speech.getSpeechId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));
    var untouched = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(untouched.getExecutionEpoch()).isZero();
  }

  @Test
  void aStaleOldGenerationMessageCannotTouchARetriedSpeech() {
    var speech = synthesizeFailed();
    module.retry(user, session.getSessionId(), speech.getSpeechId());
    assertThat(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow()
        .getExecutionEpoch()).isEqualTo(1);

    // The dead-lettered old-generation message (epoch 0) is terminal at inspect: the
    // speech/task epoch pair moved, so it must neither claim nor dead-letter the new one.
    var staleMessage = message(speech.getSpeechId(), 0);

    assertThat(handler.inspect(staleMessage).terminal()).isTrue();
    assertThat(handler.synthesize(staleMessage)).isEqualTo(VoiceSynthesisHandler.Outcome.STALE);

    var fenced = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(fenced.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(fenced.getExecutionEpoch()).isEqualTo(1);
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId())
        .orElseThrow().getExecutionEpoch()).isEqualTo(1);
  }

  @Test
  void retriedSpeechCanBeSynthesizedAgainWithAFreshEtag() throws Exception {
    var speech = synthesizeFailed();
    module.retry(user, session.getSessionId(), speech.getSpeechId());
    var retried = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    long retriedVersion = retried.getVersion();

    readySynthesizer("retried audio");
    handler.synthesize(message(speech.getSpeechId(), 1));

    var ready = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(ready.getStatus()).isEqualTo(QuestionSpeechStatus.READY);
    assertThat(ready.getExecutionEpoch()).isEqualTo(1);
    assertThat(ready.getStorageKey()).isNotNull();
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId())
        .orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    try (var media = module.open(user, session.getSessionId(), ready.getSpeechId(), null)) {
      assertThat(new String(media.resource().inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("retried audio");
      // The retried synthesis is a new generation: its weak etag carries the new version.
      assertThat(media.etag())
          .isEqualTo("W/\"qs-" + ready.getSpeechId() + "-v" + ready.getVersion() + "\"");
      assertThat(media.etag())
          .isNotEqualTo("W/\"qs-" + ready.getSpeechId() + "-v" + retriedVersion + "\"");
    }
  }

  // ---------------------------------------------------------------- open / media

  @Test
  void openStreamsTheReadyMediaFullyWithRowContentTypeAndVersionEtag() throws Exception {
    var speech = synthesizeReady("0123456789");

    try (var media = module.open(user, session.getSessionId(), speech.getSpeechId(), null)) {
      assertThat(media.resource().contentLength()).isEqualTo(10);
      assertThat(media.resource().mediaType()).isEqualTo("audio/mpeg");
      assertThat(media.resource().path()).isNotNull();
      assertThat(bytes(media)).isEqualTo("0123456789");
      assertThat(media.etag())
          .isEqualTo("W/\"qs-" + speech.getSpeechId() + "-v" + speech.getVersion() + "\"");
    }
  }

  @Test
  void openSlicesEverySingleByteRangeForm() throws Exception {
    var speech = synthesizeReady("0123456789");

    try (var media = module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createByteRange(2, 5))) {
      assertThat(bytes(media)).isEqualTo("2345");
      assertThat(media.resource().contentLength()).isEqualTo(10);
    }
    try (var media = module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createByteRange(6))) {
      assertThat(bytes(media)).isEqualTo("6789");
    }
    try (var media = module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createSuffixRange(3))) {
      assertThat(bytes(media)).isEqualTo("789");
    }
    // A suffix longer than the file is the whole file.
    try (var media = module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createSuffixRange(100))) {
      assertThat(bytes(media)).isEqualTo("0123456789");
    }
  }

  @Test
  void openRejectsUnsatisfiableRangesWithTheTotalLength() {
    var speech = synthesizeReady("0123456789");

    assertThatThrownBy(() -> module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createByteRange(10, 20)))
        .isInstanceOfSatisfying(VoiceRangeNotSatisfiableException.class, error ->
            assertThat(error.contentLength()).isEqualTo(10));
    assertThatThrownBy(() -> module.open(user, session.getSessionId(), speech.getSpeechId(),
        HttpRange.createSuffixRange(0)))
        .isInstanceOf(VoiceRangeNotSatisfiableException.class);
  }

  @Test
  void openHidesForeignAndCrossSessionSpeechesAsNotFound() {
    var speech = synthesizeReady("0123456789");

    var other = users.save(UserAccountEntity.register(
        "speech-open-other@example.com", "hash", "Other"));
    var strangerSession = seedSession(InterviewMode.VOICE, SessionStatus.INTERVIEWING);
    seedTurn(strangerSession, 1);
    var stranger = new CurrentUser(
        other.getId(), other.getUserId(), other.getEmail(), other.getDisplayName());
    assertThatThrownBy(() -> module.open(stranger, strangerSession.getSessionId(),
        speech.getSpeechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND));

    // The same user owns both sessions: a speech of session A read through session B is
    // still hidden as not found (no cross-session leakage).
    var otherSession = seedSession(InterviewMode.VOICE, SessionStatus.INTERVIEWING);
    seedTurn(otherSession, 1);
    assertThatThrownBy(() -> module.open(user, otherSession.getSessionId(),
        speech.getSpeechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND));
  }

  @Test
  void openRejectsNonReadySpeechesWithTheStableCodes() {
    var scheduled = module.getOrSchedule(user, session.getSessionId(), 1);
    assertThatThrownBy(() -> module.open(user, session.getSessionId(),
        scheduled.speechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });

    var synthesizing = speeches.findBySpeechId(scheduled.speechId()).orElseThrow();
    synthesizing.moveTo(QuestionSpeechStatus.SYNTHESIZING);
    speeches.saveAndFlush(synthesizing);
    assertThatThrownBy(() -> module.open(user, session.getSessionId(),
        scheduled.speechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));

    var failed = synthesizeFailed();
    assertThatThrownBy(() -> module.open(user, session.getSessionId(),
        failed.getSpeechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_FAILED);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
  }

  @Test
  void openReturnsNotFoundWhenTheReadyRowHasNoMediaFile() {
    var speech = synthesizeReady("0123456789");
    mediaStore.delete(speech.getStorageKey());

    assertThatThrownBy(() -> module.open(user, session.getSessionId(),
        speech.getSpeechId(), null))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND));
  }

  // ---------------------------------------------------------------- helpers

  /** Ensures the turn's speech row exists (scheduling on first access), then runs the pipeline. */
  private QuestionSpeechEntity synthesizeReady(String audio) {
    module.getOrSchedule(user, session.getSessionId(), 1);
    readySynthesizer(audio);
    var speech = speeches.findByTurnId(turn.getId()).orElseThrow();
    handler.synthesize(message(speech.getSpeechId(), 0));
    var ready = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(ready.getStatus()).isEqualTo(QuestionSpeechStatus.READY);
    return ready;
  }

  private QuestionSpeechEntity synthesizeFailed() {
    module.getOrSchedule(user, session.getSessionId(), 1);
    fakeSynthesizer.respondWith((text, profile) -> {
      throw new SpeechSynthesisFailedException("DashScope rejected the synthesis request (400)");
    });
    var speech = speeches.findByTurnId(turn.getId()).orElseThrow();
    handler.synthesize(message(speech.getSpeechId(), 0));
    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    return failed;
  }

  private void readySynthesizer(String audio) {
    fakeSynthesizer.respondWith((text, profile) -> new SynthesizedSpeech(
        audio.getBytes(StandardCharsets.UTF_8), "audio/mpeg", "fake-request"));
  }

  private TaskMessage message(UUID speechId, int epoch) {
    var task = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId).orElseThrow();
    return new TaskMessage(task.getTaskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId, epoch);
  }

  private static String bytes(QuestionSpeechMedia media) {
    try {
      return new String(media.resource().inputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private InterviewSessionEntity seedSession(InterviewMode mode, SessionStatus status) {
    var session = sessions.save(InterviewSessionEntity.preparing(
        account.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen-plus",
        "{}", null, mode,
        "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
            + "\"ttsProvider\":\"dashscope\",\"ttsModel\":\"cosyvoice-v3-flash\",\"voice\":\"longanyang\","
            + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"));
    if (status == SessionStatus.INTERVIEWING) {
      session.preparationReady();
      session.beginFixedInterview();
    }
    return sessions.saveAndFlush(session);
  }

  private InterviewTurnEntity seedTurn(InterviewSessionEntity session, int turnNo) {
    var card = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    return turns.save(InterviewTurnEntity.asked(
        session.getId(), turnNo, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), "请自我介绍"));
  }

  @TestConfiguration
  static class FakeSynthesizerConfig {
    @Bean
    @Primary
    FakeSpeechSynthesizer fakeSpeechSynthesizer() {
      return new FakeSpeechSynthesizer();
    }
  }
}
