package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskMessagePublisher;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.api.StartInterviewResponse;
import interview.pilot.interview.application.FixedAnswerService;
import interview.pilot.interview.application.FollowUpGenerator;
import interview.pilot.interview.application.StartInterviewService;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.application.FakeSpeechSynthesizer;
import interview.pilot.voice.application.QuestionSpeechHashes;
import interview.pilot.voice.application.SpeechSynthesisFailedException;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.SynthesizedSpeech;
import interview.pilot.voice.application.VoiceSynthesisHandler;
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.storage.VoiceMediaStore;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end synthesis pipeline against MySQL (V21 schema) and Redis with the real
 * {@code RedisProcessingClaim}: StartInterviewService creates the intro turn with its
 * question_speech row and synthesis task in one transaction (plan §11) → listener → READY
 * with stored audio metadata. The {@link FakeSpeechSynthesizer} is the seam (plan §5.3); its
 * default audio echoes the synthesized text so the test observes the question text that
 * reached the provider. Epoch fencing, text_sha256 drift and the "a synthesis failure never
 * blocks answering" guarantee are exercised end to end.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/voice-synthesis-listener-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-synthesis-it",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "app.async.voice-synthesis-listener.auto-startup=false"
})
@Testcontainers
class VoiceSynthesisListenerIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_voice_synthesis");

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

  @MockitoBean
  private FollowUpGenerator followUps;

  @MockitoSpyBean
  private TaskRetryPolicy retryPolicy;

  @Autowired
  private VoiceSynthesisListener listener;

  @Autowired
  private VoiceSynthesisHandler handler;

  @Autowired
  private StartInterviewService startInterview;

  @Autowired
  private FakeSpeechSynthesizer fakeSynthesizer;

  @Autowired
  private QuestionSpeechRepository speeches;

  @Autowired
  private AsyncTaskRepository tasks;

  @Autowired
  private InterviewSessionRepository sessions;

  @Autowired
  private InterviewTurnRepository turns;

  @Autowired
  private interview.pilot.interview.infrastructure.AnswerAttemptRepository attempts;

  @Autowired
  private InterviewQuestionCardRepository cards;

  @Autowired
  private UserAccountRepository users;

  @Autowired
  private FixedAnswerService answers;

  @Autowired
  private VoiceMediaStore mediaStore;

  @Autowired
  private ObjectMapper json;

  private CurrentUser user;
  private InterviewSessionEntity session;

  @BeforeEach
  void setUp() {
    fakeSynthesizer.reset();
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/mpeg", Duration.ofSeconds(3)));
    when(followUps.generate(any(), any(), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn("如果本轮的追问超时了，你会怎么处理并说明理由？");
    attempts.deleteAll();
    tasks.deleteAll();
    speeches.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    var account = users.save(UserAccountEntity.register(
        "voice-synthesis-it@example.com", "hash", "Voice Synthesis"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedReadySession();
    cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    // The fixed flow asks the first FUNDAMENTALS main question after the self introduction.
    cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.FUNDAMENTALS, 1, "技术基础", "请介绍你的技术栈",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 1, null));
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void startCreatesOneSpeechRowAndTaskPerTurnAndTheListenerMovesThemToReady() throws Exception {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(speech.getTextSha256())
        .isEqualTo(QuestionSpeechHashes.of(start.currentTurn().question()));
    assertThat(speech.getProviderId()).isEqualTo("dashscope");
    assertThat(speech.getModelName()).isEqualTo("cosyvoice-v3-flash");
    assertThat(speech.getVoiceName()).isEqualTo("longanyang");
    var task = taskOf(speech);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);

    listener.receive(message(speech, 0), source(0));

    var ready = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(ready.getStatus()).isEqualTo(QuestionSpeechStatus.READY);
    assertThat(ready.getStorageKey())
        .startsWith(user.userId() + "/" + session.getId() + "/speech/");
    assertThat(ready.getStorageKey()).endsWith("/audio");
    assertThat(ready.getContentType()).isEqualTo("audio/mpeg");
    assertThat(ready.getSizeBytes()).isPositive();
    assertThat(ready.getDurationMillis()).isEqualTo(3_000);
    assertThat(ready.getProviderRequestId()).startsWith("fake-request-");
    assertThat(ready.getExecutionEpoch()).isZero();
    assertThat(ready.getSafeError()).isNull();
    // The fake's audio echoes the synthesized text: the seam received the turn question.
    try (var resource = mediaStore.open(ready.getStorageKey())) {
      assertThat(new String(resource.inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .contains(start.currentTurn().question());
    }
    var terminalTask = taskOf(ready);
    assertThat(terminalTask.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(terminalTask.getAttemptCount()).isEqualTo(1);
    assertThat(terminalTask.getExecutionEpoch()).isZero();
  }

  @Test
  void duplicateDeliveryAfterReadyIsTerminalAndDoesNotRequeueOrDeadLetter() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    listener.receive(message(speech, 0), source(0));
    assertThat(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow()
        .getStatus()).isEqualTo(QuestionSpeechStatus.READY);

    // A duplicate delivery (crash between the final transaction and the ack) must be
    // terminal: begin and markDead both refuse a READY speech, and a non-terminal
    // classification would dead-letter and requeue the message forever.
    assertThat(handler.inspect(message(speech, 0)).terminal()).isTrue();
    clearInvocations(retryPolicy);
    listener.receive(message(speech, 0), source(0));

    verify(retryPolicy, never()).routeFailure(any(), any());
    assertThat(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow()
        .getStatus()).isEqualTo(QuestionSpeechStatus.READY);
    assertThat(taskOf(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow())
        .getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
  }

  // ---------------------------------------------------------------- deterministic failures

  @Test
  void deterministicFailureMovesTheSpeechToFailedWithTaskFailedAndLeavesTheInterviewUntouched() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    fakeSynthesizer.respondWith((text, profile) -> {
      throw new SpeechSynthesisFailedException("DashScope rejected the synthesis request (400)");
    });

    listener.receive(message(speech, 0), source(0));

    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(failed.getStorageKey()).isNull();
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    // The interview is untouched: the turn stays ASKED and the session keeps INTERVIEWING.
    var turn = turns.findById(speech.getTurnId()).orElseThrow();
    assertThat(turn.getStatus()).isEqualTo(TurnStatus.ASKED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.INTERVIEWING);
  }

  @Test
  void emptyAudioIsADeterministicFailure() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    fakeSynthesizer.respondWith((text, profile) ->
        new SynthesizedSpeech(new byte[0], "audio/mpeg", "req-empty"));

    listener.receive(message(speech, 0), source(0));

    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  @Test
  void unsupportedAudioIsADeterministicFailure() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    fakeSynthesizer.respondWith((text, profile) ->
        new SynthesizedSpeech(
            "not really audio".getBytes(StandardCharsets.UTF_8), "audio/mpeg", "req-x"));
    when(probe.probe(any())).thenThrow(new VoiceMediaUnsupportedException());

    listener.receive(message(speech, 0), source(0));

    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  // ---------------------------------------------------------------- text_sha256 guard

  @Test
  void aDriftedQuestionTextFailsTheSynthesisDeterministically() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    // Simulate drift: a row whose pinned digest no longer matches the turn text (question
    // text is immutable in the normal flow — this guards against corruption).
    speeches.delete(speech);
    var drifted = speeches.saveAndFlush(QuestionSpeechEntity.pending(
        speech.getUserAccountId(), speech.getSpeechId(), speech.getSessionId(),
        speech.getTurnId(), QuestionSpeechHashes.of("different text"),
        "dashscope", "cosyvoice-v3-flash", "longanyang"));
    var task = taskOf(drifted);
    task.setStatus(AsyncTaskStatus.PENDING);
    task.setExecutionEpoch(0);
    task.setAttemptCount(0);
    tasks.saveAndFlush(task);

    listener.receive(message(drifted, 0), source(0));

    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
  }

  // ---------------------------------------------------------------- retryable failures

  @Test
  void retryableFailureKeepsTheSpeechSynthesizingAndDeadLettersToFailedAfterExhaustion() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    fakeSynthesizer.respondWith((text, profile) -> {
      throw new SpeechSynthesisRetryableException("provider down", 0);
    });

    // First failure: retry count 0 → the delayed-retry pipeline (mock publisher), row fenced.
    listener.receive(message(speech, 0), source(0));
    var inFlight = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(inFlight.getStatus()).isEqualTo(QuestionSpeechStatus.SYNTHESIZING);
    var task = taskOf(inFlight);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PUBLISHED);
    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastError()).isNotNull();

    // Exhaustion: retry count 3 → dead-letter → markDead moves the speech to FAILED.
    listener.receive(message(speech, 0), source(3));
    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
  }

  // ---------------------------------------------------------------- markDead

  @Test
  void markDeadConvertsAnUnclaimedPendingSpeechToFailed() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);

    assertThat(handler.markDeadCurrent(message(speech, 0))).isTrue();

    var failed = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(taskOf(failed).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
  }

  @Test
  void markDeadFencesAnOlderTaskGeneration() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    var task = taskOf(speech);
    task.setExecutionEpoch(1); // a retry bumped the task generation (Task 8 moves both rows)
    tasks.saveAndFlush(task);

    // A dead-lettered old-generation message must not terminalize the newer generation:
    // the speech epoch (still 0) no longer matches the message epoch, so markDead refuses.
    assertThat(handler.markDead(message(speech, 0), 0)).isFalse();
    var fenced = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(fenced.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(taskOf(fenced).getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  // ---------------------------------------------------------------- epoch fence

  @Test
  void aStaleGenerationMessageIsIgnoredAtInspectWithoutTouchingTheRow() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    var task = taskOf(speech);
    task.setExecutionEpoch(1);
    tasks.saveAndFlush(task);

    assertThat(handler.inspect(message(speech, 0)).terminal()).isTrue();
    listener.receive(message(speech, 0), source(0));

    var untouched = speeches.findBySpeechId(speech.getSpeechId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId())
        .orElseThrow().getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  // ---------------------------------------------------------------- TTS failure never blocks answering

  @Test
  void aFailedSynthesisDoesNotBlockTheAnswerFlowAndTheNextTurnGetsItsOwnSpeech() {
    var start = startInterview.start(user, session.getSessionId());
    var speech = speechOf(start);
    fakeSynthesizer.respondWith((text, profile) -> {
      throw new SpeechSynthesisFailedException("provider down");
    });
    listener.receive(message(speech, 0), source(0));
    assertThat(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow()
        .getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);

    var request = new SubmitAnswerRequest(UUID.randomUUID(), "有效回答");
    var claim = answers.claim(user, session.getSessionId(), request);
    var result = answers.process(claim);

    assertThat(result.idempotentReplay()).isFalse();
    var nextTurn = turns.findAllBySessionIdOrderByTurnNo(session.getId()).stream()
        .filter(turn -> turn.getTurnNo() == 2).findFirst().orElseThrow();
    assertThat(nextTurn.getQuestionText()).isEqualTo("请介绍你的技术栈");
    // The next turn got its own speech row + task (VOICE + TTS configured), exactly one each.
    var nextSpeech = speeches.findByTurnId(nextTurn.getId()).orElseThrow();
    assertThat(nextSpeech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + nextSpeech.getSpeechId(),
        speech.getUserAccountId())).isPresent();
    // The failed speech of turn 1 stays failed and the session keeps moving.
    assertThat(speeches.findBySpeechId(speech.getSpeechId()).orElseThrow()
        .getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getCurrentTurnNo()).isEqualTo(2);
  }

  // ---------------------------------------------------------------- helpers

  private InterviewSessionEntity seedReadySession() {
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
            + "\"ttsProvider\":\"dashscope\",\"ttsModel\":\"cosyvoice-v3-flash\",\"voice\":\"longanyang\","
            + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"));
    session.preparationReady();
    return sessions.saveAndFlush(session);
  }

  private QuestionSpeechEntity speechOf(StartInterviewResponse start) {
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), start.currentTurn().turnNo())
        .orElseThrow();
    return speeches.findByTurnId(turn.getId()).orElseThrow();
  }

  private AsyncTaskEntity taskOf(QuestionSpeechEntity speech) {
    return tasks.findByTaskTypeAndBizKey(
            AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
            QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId())
        .orElseThrow();
  }

  private TaskMessage message(QuestionSpeechEntity speech, int epoch) {
    var task = taskOf(speech);
    return new TaskMessage(task.getTaskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(), epoch);
  }

  private static Message source(int retryCount) {
    var properties = new MessageProperties();
    if (retryCount > 0) {
      properties.setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, retryCount);
    }
    return new Message(new byte[0], properties);
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
