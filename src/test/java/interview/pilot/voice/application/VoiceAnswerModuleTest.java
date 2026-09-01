package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.PendingTaskDispatcher;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskMessagePublisher;
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
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * End-to-end recording upload tests against MySQL (real repositories, V21 schema) with the
 * real {@code FileSystemVoiceMediaStore} and a fake {@link AudioProbe} — media validation is
 * exercised against the file-system store exactly as the carry-over requires.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/voice-module-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-module-test",
    "DASHSCOPE_WORKSPACE_ID=",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "spring.autoconfigure.exclude="
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@Testcontainers
class VoiceAnswerModuleTest {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_voice_test");

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
  private AudioProbe probe;

  @Autowired
  private VoiceAnswerModule module;

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

  @MockitoSpyBean
  private VoiceMediaStore mediaStore;

  @Autowired
  private VoiceProperties properties;

  @Autowired
  private PendingTaskDispatcher dispatcher;

  private CurrentUser user;
  private UserAccountEntity account;
  private InterviewSessionEntity session;
  private InterviewTurnEntity turn;

  @BeforeEach
  void setUp() {
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/webm", Duration.ofSeconds(30)));
    tasks.deleteAll();
    recordings.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    clearMediaRoot();
    account = users.save(UserAccountEntity.register(
        "voice-module@example.com", "hash", "Voice Module"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedSession(SessionStatus.INTERVIEWING);
    turn = seedTurn(session, 1);
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void acceptTransitionsReceivingToUploadedWithMetadataTaskAndStoredMedia() throws Exception {
    byte[] bytes = "hello voice".getBytes(StandardCharsets.UTF_8);
    UUID requestId = UUID.randomUUID();

    var receipt = module.accept(user, session.getSessionId(), 1, requestId, audio(bytes));

    assertThat(receipt.recordingId()).isNotNull();
    assertThat(receipt.status()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(receipt.transcriptionTaskId()).isNotNull();

    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(recording.getUploadRequestId()).isEqualTo(requestId);
    assertThat(recording.getSessionId()).isEqualTo(session.getId());
    assertThat(recording.getTurnId()).isEqualTo(turn.getId());
    assertThat(recording.getUserAccountId()).isEqualTo(account.getId());
    assertThat(recording.getStorageKey()).isEqualTo(new VoiceMediaKey(
        account.getUserId(), session.getId(), VoiceMediaKind.RECORDING,
        receipt.recordingId()).storageKey());
    assertThat(recording.getContentType()).isEqualTo("audio/webm");
    assertThat(recording.getSizeBytes()).isEqualTo(bytes.length);
    assertThat(recording.getDurationMillis()).isEqualTo(30_000);
    assertThat(recording.getSha256()).isEqualTo(sha256(bytes));
    assertThat(recording.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(9)));
    try (var resource = mediaStore.open(recording.getStorageKey())) {
      assertThat(resource.inputStream().readAllBytes()).isEqualTo(bytes);
    }

    var task = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.VOICE_TRANSCRIPTION,
        "voice-recording:" + receipt.recordingId()).orElseThrow();
    assertThat(task.getTaskId()).isEqualTo(receipt.transcriptionTaskId());
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isZero();
    assertThat(task.getAttemptCount()).isZero();
    assertThat(task.getPayloadSnapshot()).contains(receipt.recordingId().toString());
    assertThat(tasks.count()).isEqualTo(1);

    // The PENDING row is picked up by the existing reliable publish path.
    dispatcher.dispatchPendingTasks();
    verify(publisher).publish(new TaskMessage(
        task.getTaskId(), AsyncTaskType.VOICE_TRANSCRIPTION,
        "voice-recording:" + receipt.recordingId(), 0));
  }

  @Test
  void getExposesTheViewWithTurnNoAndHidesTranscriptUntilReady() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));

    var view = module.get(user, session.getSessionId(), receipt.recordingId());

    assertThat(view.recordingId()).isEqualTo(receipt.recordingId());
    assertThat(view.turnNo()).isEqualTo(1);
    assertThat(view.status()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(view.rawTranscript()).isNull();
    assertThat(view.durationMillis()).isEqualTo(30_000);
    assertThat(view.retryable()).isFalse();
    assertThat(view.safeError()).isNull();
  }

  // ---------------------------------------------------------------- idempotency

  @Test
  void sameRequestIdWithSameBytesReplaysTheSameRecordingWithoutASecondTask() throws Exception {
    byte[] bytes = "same bytes".getBytes(StandardCharsets.UTF_8);
    UUID requestId = UUID.randomUUID();
    var first = module.accept(user, session.getSessionId(), 1, requestId, audio(bytes));

    var replay = module.accept(user, session.getSessionId(), 1, requestId, audio(bytes));

    assertThat(replay.recordingId()).isEqualTo(first.recordingId());
    assertThat(replay.status()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(replay.transcriptionTaskId()).isEqualTo(first.transcriptionTaskId());
    assertThat(recordings.count()).isEqualTo(1);
    assertThat(tasks.count()).isEqualTo(1);
    var recording = recordings.findByRecordingId(first.recordingId()).orElseThrow();
    try (var resource = mediaStore.open(recording.getStorageKey())) {
      assertThat(resource.inputStream().readAllBytes()).isEqualTo(bytes);
    }
  }

  @Test
  void sameRequestIdWithDifferentBytesIsAStableConflictThatLeavesTheOriginalMediaUntouched()
      throws Exception {
    UUID requestId = UUID.randomUUID();
    var first = module.accept(user, session.getSessionId(), 1, requestId, audio("original"));

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, requestId, audio("tampered")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo("REQUEST_ID_CONFLICT");
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });

    var recording = recordings.findByRecordingId(first.recordingId()).orElseThrow();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(recordings.count()).isEqualTo(1);
    assertThat(tasks.count()).isEqualTo(1);
    // The digest comparison happens before any store call: the live media survives intact.
    try (var resource = mediaStore.open(recording.getStorageKey())) {
      assertThat(resource.inputStream().readAllBytes())
          .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void concurrentDuplicateUploadsProduceExactlyOneUploadedRecordingAndTask() throws Exception {
    byte[] bytes = "concurrent".getBytes(StandardCharsets.UTF_8);
    UUID requestId = UUID.randomUUID();
    var start = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return acceptOrConflict(module, user, session.getSessionId(), requestId, audio(bytes));
      });
      var second = executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return acceptOrConflict(module, user, session.getSessionId(), requestId, audio(bytes));
      });

      List<Object> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      var receipts = outcomes.stream().filter(VoiceRecordingReceipt.class::isInstance)
          .map(VoiceRecordingReceipt.class::cast).toList();
      var rejections = outcomes.stream().filter(BusinessException.class::isInstance)
          .map(BusinessException.class::cast).toList();
      assertThat(receipts).isNotEmpty();
      assertThat(receipts).extracting(VoiceRecordingReceipt::recordingId)
          .containsOnly(receipts.getFirst().recordingId());
      assertThat(rejections).allSatisfy(error ->
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS));
      assertThat(recordings.count()).isEqualTo(1);
      assertThat(tasks.count()).isEqualTo(1);
      assertThat(recordings.findAll())
          .singleElement()
          .extracting(VoiceRecordingEntity::getStatus)
          .isEqualTo(VoiceRecordingStatus.UPLOADED);
    } finally {
      executor.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- rejections

  @Test
  void oversizedUploadIs413WithNoTaskAndAFailedRecording() {
    byte[] bytes = new byte[9_000_000];

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio(bytes)))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE);
          assertThat(error.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        });

    assertThat(recordings.findAll())
        .singleElement()
        .satisfies(recording -> {
          assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
          assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE);
          assertThat(recording.getSha256()).isNull(); // streaming was interrupted
          assertThat(recording.getStorageKey()).isNull();
        });
    assertThat(tasks.count()).isZero();
  }

  @Test
  void unsupportedMediaIs415AndRecordsTheDigestOnTheFailedRow() {
    when(probe.probe(any())).thenThrow(new interview.pilot.voice.domain.VoiceMediaUnsupportedException());
    byte[] bytes = "not audio".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio(bytes)))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED);
          assertThat(error.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        });

    var recording = recordings.findAll().getFirst();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED);
    assertThat(recording.getSha256()).isEqualTo(sha256(bytes));
    assertThat(tasks.count()).isZero();

    // Equal-content replay compares the staged digest and returns the recording with its
    // current FAILED status — the requestId outcome stays fixed and the store is untouched.
    UUID requestId = recording.getUploadRequestId();
    var replay = module.accept(user, session.getSessionId(), 1, requestId, audio(bytes));
    assertThat(replay.recordingId()).isEqualTo(recording.getRecordingId());
    assertThat(replay.status()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(replay.transcriptionTaskId()).isNull();
    assertThat(recordings.count()).isEqualTo(1);
  }

  @Test
  void probeFailureMarksTheRecordingFailedWithDiagnosticAndReplaysTheFailure() {
    when(probe.probe(any())).thenThrow(new VoiceMediaProbeException("ffprobe timed out", null));
    byte[] bytes = "mystery media".getBytes(StandardCharsets.UTF_8);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, requestId, audio(bytes)))
        .isInstanceOf(VoiceMediaProbeException.class);

    var recording = recordings.findAll().getFirst();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_PROBE_FAILED);
    assertThat(recording.getSha256()).isEqualTo(sha256(bytes)); // digest kept for replay
    assertThat(recording.getStorageKey()).isNull();
    assertThat(tasks.count()).isZero();

    // Equal-content replay returns the recording with its current FAILED status.
    var replay = module.accept(user, session.getSessionId(), 1, requestId, audio(bytes));
    assertThat(replay.recordingId()).isEqualTo(recording.getRecordingId());
    assertThat(replay.status()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recordings.count()).isEqualTo(1);
  }

  @Test
  void storageFailureMarksTheRecordingFailedInsteadOfStrandingItReceiving() throws Exception {
    doThrow(new VoiceMediaStorageException("disk full", new java.io.IOException()))
        .when(mediaStore).store(any(VoiceMediaKey.class), any(Path.class), anyLong(),
            any(ProbedAudio.class));
    byte[] bytes = "storable".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio(bytes)))
        .isInstanceOf(VoiceMediaStorageException.class);

    var recording = recordings.findAll().getFirst();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_STORAGE_FAILED);
    assertThat(recording.getSha256()).isEqualTo(sha256(bytes));
    assertThat(tasks.count()).isZero();
  }

  @Test
  void uploadLevelFailuresAreNotRetryableWhileTranscriptionFailuresAre() {
    // Upload-level failure: no media, retry refuses — the view must not promise a retry.
    when(probe.probe(any())).thenThrow(new interview.pilot.voice.domain.VoiceMediaUnsupportedException());
    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("bad")));
    var uploadFailed = recordings.findAll().getFirst();
    var uploadView = module.get(user, session.getSessionId(), uploadFailed.getRecordingId());
    assertThat(uploadView.status()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(uploadView.retryable()).isFalse();
    assertThat(uploadView.safeError()).isEqualTo(VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED);

    // Transcription-level failure: media exists, retry is the recovery path. doReturn (not
    // when) so the re-stub does not execute the still-active thenThrow stub above.
    doReturn(new ProbedAudio("audio/webm", Duration.ofSeconds(30))).when(probe).probe(any());
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("ok"));
    var transcribing = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    transcribing.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    transcribing.moveTo(VoiceRecordingStatus.FAILED);
    recordings.saveAndFlush(transcribing);
    var transcriptionView = module.get(user, session.getSessionId(), receipt.recordingId());
    assertThat(transcriptionView.status()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(transcriptionView.retryable()).isTrue();
  }

  @Test
  void durationBeyondTheLimitIs413LikeTooLargeContent() {
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/webm", Duration.ofMinutes(6)));
    byte[] bytes = "long audio".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio(bytes)))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_DURATION_EXCEEDED);
          assertThat(error.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        });

    var recording = recordings.findAll().getFirst();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_DURATION_EXCEEDED);
    assertThat(recording.getSha256()).isEqualTo(sha256(bytes));
    assertThat(tasks.count()).isZero();
  }

  @Test
  void rejectsUploadsForANonCurrentTurn() {
    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 2, UUID.randomUUID(), audio("a")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(recordings.count()).isZero();
  }

  @Test
  void hidesUnknownAndForeignSessionsAsNotFound() {
    assertThatThrownBy(() ->
        module.accept(user, UUID.randomUUID(), 1, UUID.randomUUID(), audio("a")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND);
          assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });

    var other = users.save(UserAccountEntity.register(
        "other@example.com", "hash", "Other"));
    var otherSession = seedSession(SessionStatus.INTERVIEWING);
    var stranger = new CurrentUser(
        other.getId(), other.getUserId(), other.getEmail(), other.getDisplayName());
    assertThatThrownBy(() ->
        module.accept(stranger, otherSession.getSessionId(), 1, UUID.randomUUID(), audio("a")))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND));
  }

  @Test
  void rejectsUploadsWhenTheSessionIsNotInterviewing() {
    var ready = seedSession(SessionStatus.READY);

    assertThatThrownBy(() ->
        module.accept(user, ready.getSessionId(), 1, UUID.randomUUID(), audio("a")))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT));
  }

  // ---------------------------------------------------------------- discard

  @Test
  void discardFromReadyDeletesTheMediaAndIsIdempotent() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    recording.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    recording.moveTo(VoiceRecordingStatus.READY);
    recordings.saveAndFlush(recording);

    module.discard(user, session.getSessionId(), receipt.recordingId());
    module.discard(user, session.getSessionId(), receipt.recordingId());

    var discarded = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(discarded.getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);
    assertThatThrownBy(() -> mediaStore.open(discarded.getStorageKey()))
        .isInstanceOf(VoiceMediaNotFoundException.class);
  }

  @Test
  void discardFromFailedWithAbsentMediaAndFromReceivingSucceeds() {
    when(probe.probe(any())).thenThrow(new interview.pilot.voice.domain.VoiceMediaUnsupportedException());
    UUID failedRequestId = UUID.randomUUID();
    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, failedRequestId, audio("a")));
    var failed = recordings.findAll().getFirst();
    module.discard(user, session.getSessionId(), failed.getRecordingId());
    assertThat(recordings.findById(failed.getId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.DISCARDED);

    UUID receivingRequestId = UUID.randomUUID();
    recordings.save(VoiceRecordingEntity.receiving(
        account.getId(), UUID.randomUUID(), receivingRequestId, session.getId(), turn.getId(),
        Instant.now().plus(Duration.ofMinutes(10))));
    var receiving = recordings.findByUploadRequestId(receivingRequestId).orElseThrow();
    module.discard(user, session.getSessionId(), receiving.getRecordingId());
    assertThat(recordings.findById(receiving.getId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.DISCARDED);
  }

  @Test
  void discardFromAttachedAndUploadedIsRejected() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    recording.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    recording.moveTo(VoiceRecordingStatus.READY);
    recording.moveTo(VoiceRecordingStatus.ATTACHED);
    recordings.saveAndFlush(recording);

    assertThatThrownBy(() ->
        module.discard(user, session.getSessionId(), receipt.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED));
    assertThat(recordings.findById(recording.getId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.ATTACHED);

    var uploading = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("b"));
    assertThatThrownBy(() ->
        module.discard(user, session.getSessionId(), uploading.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_IN_PROGRESS));
  }

  // ---------------------------------------------------------------- retry

  @Test
  void retryFromFailedTransitionsToTranscribingFencesEpochsAndRepublishes() {
    var receipt = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a"));
    var recording = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    recording.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    recording.moveTo(VoiceRecordingStatus.FAILED);
    recordings.saveAndFlush(recording);

    module.retry(user, session.getSessionId(), receipt.recordingId());

    var retried = recordings.findByRecordingId(receipt.recordingId()).orElseThrow();
    assertThat(retried.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(retried.getExecutionEpoch()).isEqualTo(1);
    var task = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.VOICE_TRANSCRIPTION, "voice-recording:" + receipt.recordingId()).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isEqualTo(1);
    assertThat(task.getLastPublishedAt()).isNull();
    assertThat(tasks.count()).isEqualTo(1);

    dispatcher.dispatchPendingTasks();
    verify(publisher).publish(new TaskMessage(
        task.getTaskId(), AsyncTaskType.VOICE_TRANSCRIPTION,
        "voice-recording:" + receipt.recordingId(), 1));
  }

  @Test
  void retryFromNonFailedStatesIsRejectedWithTheStateCode() {
    var uploading = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("u"));
    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), uploading.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_IN_PROGRESS));

    var ready = recordings.findByRecordingId(uploading.recordingId()).orElseThrow();
    ready.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    ready.moveTo(VoiceRecordingStatus.READY);
    recordings.saveAndFlush(ready);
    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), uploading.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_READY));

    var attached = recordings.findByRecordingId(uploading.recordingId()).orElseThrow();
    attached.moveTo(VoiceRecordingStatus.ATTACHED);
    recordings.saveAndFlush(attached);
    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), uploading.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED));

    UUID receivingRequestId = UUID.randomUUID();
    recordings.save(VoiceRecordingEntity.receiving(
        account.getId(), UUID.randomUUID(), receivingRequestId, session.getId(), turn.getId(),
        Instant.now().plus(Duration.ofMinutes(10))));
    var receiving = recordings.findByUploadRequestId(receivingRequestId).orElseThrow();
    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), receiving.getRecordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS));

    var second = module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("d"));
    var discarded = recordings.findByRecordingId(second.recordingId()).orElseThrow();
    discarded.moveTo(VoiceRecordingStatus.TRANSCRIBING);
    discarded.moveTo(VoiceRecordingStatus.READY);
    discarded.moveTo(VoiceRecordingStatus.DISCARDED);
    recordings.saveAndFlush(discarded);
    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), second.recordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_READY));
  }

  @Test
  void retryOfAUploadLevelFailureWithoutMediaIsRejected() {
    when(probe.probe(any())).thenThrow(new interview.pilot.voice.domain.VoiceMediaUnsupportedException());
    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, UUID.randomUUID(), audio("a")));
    var failed = recordings.findAll().getFirst();

    assertThatThrownBy(() -> module.retry(user, session.getSessionId(), failed.getRecordingId()))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED));
  }

  // ---------------------------------------------------------------- crash residue

  @Test
  void aStaleReceivingRowBlocksReplayUntilTheSweeperReclaimsIt() {
    UUID requestId = UUID.randomUUID();
    var stale = recordings.save(VoiceRecordingEntity.receiving(
        account.getId(), UUID.randomUUID(), requestId, session.getId(), turn.getId(),
        Instant.now().plus(Duration.ofMinutes(10))));

    assertThatThrownBy(() ->
        module.accept(user, session.getSessionId(), 1, requestId, audio("a")))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS));
    assertThat(recordings.count()).isEqualTo(1);

    // The row is discoverable by expiry for Task 11's sweeper (idx_voice_recording_expiry).
    assertThat(stale.getExpiresAt()).isNotNull();
    assertThat(recordings.findAllByStatusInAndExpiresAtBefore(
        List.of(VoiceRecordingStatus.RECEIVING), Instant.now().plus(Duration.ofMinutes(11))))
        .extracting(VoiceRecordingEntity::getId)
        .contains(stale.getId());
  }

  // ---------------------------------------------------------------- helpers

  private InterviewSessionEntity seedSession(SessionStatus status) {
    var session = sessions.save(InterviewSessionEntity.preparing(
        account.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen-plus",
        "{}", null, InterviewMode.VOICE,
        "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
            + "\"ttsProvider\":\"unconfigured\",\"ttsModel\":\"unconfigured\",\"voice\":\"server-default\","
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

  /** Runs accept and maps the losing thread's conflict back to the exception value. */
  private static Object acceptOrConflict(
      VoiceAnswerModule module, CurrentUser user, UUID sessionId, UUID requestId,
      MockMultipartFile audio) {
    try {
      return module.accept(user, sessionId, 1, requestId, audio);
    } catch (BusinessException exception) {
      return exception;
    }
  }

  private static MockMultipartFile audio(byte[] bytes) {
    return new MockMultipartFile("audio", "recording.webm", "audio/webm", bytes);
  }

  private static MockMultipartFile audio(String content) {
    return audio(content.getBytes(StandardCharsets.UTF_8));
  }

  private void clearMediaRoot() {
    Path root = properties.filesRoot();
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var children = Files.list(root)) {
      for (Path child : children.toList()) {
        deleteRecursively(child);
      }
    } catch (Exception ignored) {
      // best-effort test hygiene
    }
  }

  private static void deleteRecursively(Path path) {
    try (var walk = Files.walk(path)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (Exception ignored) {
          // best-effort test hygiene
        }
      });
    } catch (Exception ignored) {
      // best-effort test hygiene
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
