package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskMessagePublisher;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.VoiceTranscriptionRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
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
import interview.pilot.voice.application.FakeSpeechRecognizer;
import interview.pilot.voice.application.Transcript;
import interview.pilot.voice.application.VoiceAnswerModule;
import interview.pilot.voice.application.VoiceTranscriptionHandler;
import interview.pilot.voice.application.VoiceTranscriptionRetryableException;
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.infrastructure.VoiceTranscriptionListener;
import interview.pilot.voice.storage.VoiceMediaStore;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end transcription pipeline against MySQL (V21+V22 schema) and Redis with the real
 * {@code RedisProcessingClaim}: upload (Task 4) → listener → READY. The {@link FakeSpeechRecognizer}
 * is the seam (plan §5.3); its default transcript echoes the recognition-context vocabulary
 * size so the test observes the context assembly end to end (job title + JD terms + fixed
 * list: 1 + 4 + 30 − 3 fixed-list duplicates = 32 for the seeded brief). Epoch fencing is
 * exercised against a manual retry: a stale message must never overwrite the new result.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/voice-listener-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-listener-it",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "app.async.voice-transcription-listener.auto-startup=false"
})
@Testcontainers
class VoiceTranscriptionListenerIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_voice_listener");

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
  private AudioProbe probe;

  @MockitoSpyBean
  private TaskRetryPolicy retryPolicy;

  @Autowired
  private VoiceAnswerModule module;

  @Autowired
  private VoiceMediaStore mediaStore;

  @Autowired
  private VoiceTranscriptionListener listener;

  @Autowired
  private VoiceTranscriptionHandler handler;

  @Autowired
  private FakeSpeechRecognizer fakeRecognizer;

  @Autowired
  private VoiceRecordingRepository recordings;

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
  private ObjectMapper json;

  private CurrentUser user;
  private InterviewSessionEntity session;
  private InterviewTurnEntity turn;

  @BeforeEach
  void setUp() throws Exception {
    fakeRecognizer.reset();
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/webm", Duration.ofSeconds(30)));
    tasks.deleteAll();
    recordings.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    var account = users.save(UserAccountEntity.register(
        "voice-listener-it@example.com", "hash", "Voice Listener"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedSession();
    turn = seedTurn(session, 1);
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void uploadToListenerTranscribesToReadyWithMetadataAndTerminalTask() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));

    listener.receive(message(receipt, 0), source(0));

    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.READY);
    // Vocabulary = job title + JD Latin tokens (Spring/Boot/MySQL/Redis) + fixed list.
    assertThat(recording.getRawTranscript()).isEqualTo("fake transcript (vocabulary=32)");
    assertThat(recording.getProviderId()).isEqualTo("dashscope");
    assertThat(recording.getModelName()).isEqualTo("fake-asr-model");
    assertThat(recording.getProviderRequestId()).startsWith("fake-request-");
    assertThat(recording.getAsrDurationMillis()).isNotNull();
    assertThat(recording.getExecutionEpoch()).isZero();
    assertThat(recording.getSafeError()).isNull();
    var task = taskOf(receipt);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getExecutionEpoch()).isZero();
  }

  // ---------------------------------------------------------------- deterministic failures

  @Test
  void blankTranscriptIsADeterministicFailure() {
    fakeRecognizer.respondWith(context -> new Transcript("", "req-blank", "fake-asr-model"));
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));

    listener.receive(message(receipt, 0), source(0));

    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  @Test
  void oversizedTranscriptIsADeterministicFailure() {
    fakeRecognizer.respondWith(context -> new Transcript(
        "x".repeat(VoiceTranscriptionHandler.MAX_TRANSCRIPT_CHARS + 1),
        "req-long", "fake-asr-model"));
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));

    listener.receive(message(receipt, 0), source(0));

    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  @Test
  void missingMediaAtTranscriptionTimeIsADeterministicFailure() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    mediaStore.delete(recording.getStorageKey());
    // The fake recognizer never reads the store itself; the production adapter surfaces the
    // missing media as VoiceMediaNotFoundException and the handler must map it deterministically
    // (retrying cannot restore a deleted file).
    fakeRecognizer.respondWith(context -> {
      throw new VoiceMediaNotFoundException();
    });

    listener.receive(message(receipt, 0), source(0));

    var failed = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_STORAGE_FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  @Test
  void duplicateDeliveryAfterDiscardIsTerminalAndDoesNotRequeueOrDeadLetter() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    listener.receive(message(receipt, 0), source(0));
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.READY);

    module.discard(user, session.getSessionId(), receipt.recordingId());
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);

    // A duplicate delivery (crash between the final transaction and the ack) must be
    // terminal: begin and markDead both refuse a DISCARDED recording, and a non-terminal
    // classification would dead-letter and requeue the message forever.
    assertThat(handler.inspect(message(receipt, 0)).terminal()).isTrue();
    clearInvocations(retryPolicy);
    listener.receive(message(receipt, 0), source(0));

    verify(retryPolicy, never()).routeFailure(any(), any());
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
  }

  // ---------------------------------------------------------------- retryable failures

  @Test
  void retryableFailureKeepsTheRecordingTranscribingAndDeadLettersToFailedAfterExhaustion() {
    fakeRecognizer.respondWith(context -> {
      throw new VoiceTranscriptionRetryableException("provider down", 0);
    });
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));

    // First failure: retry count 0 → the delayed-retry pipeline (mock publisher), row fenced.
    listener.receive(message(receipt, 0), source(0));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    var task = taskOf(receipt);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastError()).isNotNull();

    // Exhaustion: retry count 3 → dead-letter → markDead moves the recording to FAILED.
    listener.receive(message(receipt, 0), source(3));
    var failed = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
  }

  // ---------------------------------------------------------------- markDead

  @Test
  void markDeadConvertsAnUnclaimedUploadedRecordingToFailed() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.UPLOADED);

    assertThat(handler.markDeadCurrent(message(receipt, 0))).isTrue();

    var failed = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
  }

  @Test
  void markDeadFencesOlderGenerationsAfterAManualRetry() {
    // Deterministic failure, then manual retry → epoch 1.
    fakeRecognizer.respondWith(context -> new Transcript("", "req", "model"));
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    listener.receive(message(receipt, 0), source(0));
    module.retry(user, session.getSessionId(), receipt.recordingId());
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getExecutionEpoch()).isEqualTo(1);

    // A dead-lettered old-generation message must not terminalize the new generation.
    assertThat(handler.markDead(message(receipt, 0), 0)).isFalse();
    var fenced = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(fenced.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(fenced.getExecutionEpoch()).isEqualTo(1);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.PENDING);

    // The current generation can still be terminalized.
    assertThat(handler.markDead(message(receipt, 1), 1)).isTrue();
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);

    // The dead-lettered message itself is then a no-op at the listener.
    listener.receive(message(receipt, 1), source(3));
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
  }

  // ---------------------------------------------------------------- epoch fence

  @Test
  void manualRetryFencesStaleMessagesEndToEnd() {
    // 1. Deterministic failure at epoch 0.
    fakeRecognizer.respondWith(context -> new Transcript("", "req", "model"));
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    listener.receive(message(receipt, 0), source(0));
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);

    // 2. Manual retry: recording + task epochs move to 1 in lockstep, the terminal claim is
    //    cleared so the retried message can acquire it.
    module.retry(user, session.getSessionId(), receipt.recordingId());
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    var task = taskOf(receipt);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isEqualTo(1);

    // 3. The handler itself refuses a stale message (no state change).
    fakeRecognizer.reset();
    assertThat(handler.transcribe(message(receipt, 0)))
        .isEqualTo(VoiceTranscriptionHandler.Outcome.STALE);
    var untouched = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(untouched.getRawTranscript()).isNull();

    // 4. The stale message arriving at the listener is ignored entirely.
    listener.receive(message(receipt, 0), source(0));
    assertThat(recordings.findByRecordingId(receipt.recordingId()).orElseThrow()
        .getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);

    // 5. The retried message (epoch 1) acquires the claim and completes.
    listener.receive(message(receipt, 1), source(0));
    var ready = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(ready.getStatus()).isEqualTo(VoiceRecordingStatus.READY);
    assertThat(ready.getRawTranscript()).isEqualTo("fake transcript (vocabulary=32)");
    assertThat(ready.getExecutionEpoch()).isEqualTo(1);
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);

    // 6. Even after completion the stale message cannot overwrite the new result.
    listener.receive(message(receipt, 0), source(0));
    var still = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(still.getStatus()).isEqualTo(VoiceRecordingStatus.READY);
    assertThat(still.getRawTranscript()).isEqualTo("fake transcript (vocabulary=32)");
    assertThat(taskOf(receipt).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
  }

  // ---------------------------------------------------------------- helpers

  private InterviewSessionEntity seedSession() throws Exception {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java 后端工程师",
        "负责 Spring Boot 微服务开发，熟悉 MySQL 与 Redis",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);
    var session = sessions.save(InterviewSessionEntity.preparing(
        user.databaseId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端工程师", "dashscope", "qwen",
        json.writeValueAsString(brief), null, InterviewMode.VOICE,
        "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
            + "\"ttsProvider\":\"unconfigured\",\"ttsModel\":\"unconfigured\",\"voice\":\"server-default\","
            + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"));
    session.preparationReady();
    session.beginFixedInterview();
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

  private TaskMessage message(
      interview.pilot.voice.application.VoiceRecordingReceipt receipt, int epoch) {
    var task = taskOf(receipt);
    return new TaskMessage(task.getTaskId(), AsyncTaskType.VOICE_TRANSCRIPTION,
        VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + receipt.recordingId(), epoch);
  }

  private interview.pilot.async.infrastructure.AsyncTaskEntity taskOf(
      interview.pilot.voice.application.VoiceRecordingReceipt receipt) {
    return tasks.findByTaskTypeAndBizKey(
            AsyncTaskType.VOICE_TRANSCRIPTION,
            VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + receipt.recordingId())
        .orElseThrow();
  }

  private static Message source(int retryCount) {
    var properties = new MessageProperties();
    if (retryCount > 0) {
      properties.setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, retryCount);
    }
    return new Message(new byte[0], properties);
  }

  private static MockMultipartFile audio(String content) {
    return new MockMultipartFile(
        "audio", "recording.webm", "audio/webm",
        content.getBytes(StandardCharsets.UTF_8));
  }

  @TestConfiguration
  static class FakeRecognizerConfig {
    @Bean
    @Primary
    FakeSpeechRecognizer fakeSpeechRecognizer() {
      return new FakeSpeechRecognizer();
    }
  }
}
