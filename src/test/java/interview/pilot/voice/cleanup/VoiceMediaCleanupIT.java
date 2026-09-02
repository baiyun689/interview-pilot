package interview.pilot.voice.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
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
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.application.QuestionSpeechHashes;
import interview.pilot.voice.application.QuestionSpeechModule;
import interview.pilot.voice.application.VoiceAnswerModule;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end cleanup sweeper (Task 11) against MySQL (V21-V23 schema) and Redis with the real
 * {@code RedisProcessingClaim} and the real {@code FileSystemVoiceMediaStore} (a spy only to
 * count deletes and inject one simulated storage failure). A mutable {@link Clock} bean drives
 * every time condition (RECEIVING TTL, retention window, stuck-task staleness, orphan grace).
 *
 * <p>Every required acceptance category is pinned here: RECEIVING residue, DISCARDED cleanup,
 * retention (including the "live sessions are never touched" inverse), stuck TRANSCRIBING /
 * SYNTHESIZING recovery with the user retry path, orphan files + grace, crash-point convergence,
 * idempotency, the run-level batch claim under concurrency, and the Windows sharing-violation
 * deferral.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/voice-cleanup-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-cleanup-it",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.async.rabbit.dispatch-interval=1h",
    "app.async.voice-transcription-listener.auto-startup=false",
    "app.async.voice-synthesis-listener.auto-startup=false",
    "app.voice.cleanup-interval=PT24H",
    "app.voice.cleanup-initial-delay=PT24H"
})
@Testcontainers
class VoiceMediaCleanupIT {
  private static final String VOICE_SNAPSHOT =
      "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
          + "\"ttsProvider\":\"unconfigured\",\"ttsModel\":\"unconfigured\",\"voice\":\"server-default\","
          + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}";

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_voice_cleanup");

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
  private AudioProbe probe;

  @MockitoSpyBean
  private VoiceMediaStore mediaStore;

  @Autowired
  private VoiceMediaCleanupService cleanupService;

  @Autowired
  private VoiceRecordingRepository recordings;

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
  private VoiceAnswerModule module;

  @Autowired
  private QuestionSpeechModule speechModule;

  @Autowired
  private ProcessingClaim claims;

  @Autowired
  private VoiceProperties voice;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ObjectMapper json;

  @Autowired
  private MutableClock clock;

  private UserAccountEntity user;
  private CurrentUser currentUser;
  private InterviewSessionEntity session;

  @BeforeEach
  void setUp() throws Exception {
    clock.set(Instant.now());
    clearInvocations(mediaStore);
    wipeMediaRoot();
    tasks.deleteAll();
    speeches.deleteAll();
    recordings.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    when(probe.probe(any())).thenReturn(new ProbedAudio("audio/webm", Duration.ofSeconds(30)));
    var account = users.save(UserAccountEntity.register(
        "voice-cleanup-it@example.com", "hash", "Voice Cleanup"));
    user = account;
    currentUser = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedSession(SessionStatus.INTERVIEWING);
    seedTurn(session, 1);
  }

  // ---------------------------------------------------------------- RECEIVING residue

  @Test
  void receivingResidueDeletesExpiredRowsAndKeepsUnexpiredOnes() {
    var expired = seedRecording(session, VoiceRecordingStatus.RECEIVING,
        clock.instant().minus(Duration.ofMinutes(2)));
    var unexpired = seedRecording(session, VoiceRecordingStatus.RECEIVING,
        clock.instant().plus(Duration.ofMinutes(5)));
    // a phase-3 crash can leave a media file at the derived key of a null-key RECEIVING row
    storeFile(session, VoiceMediaKind.RECORDING, expired.getRecordingId(), "crash-left");
    // a RECEIVING row that carries a storage key (defensive) must lose its file with the row
    var keyed = seedRecording(session, VoiceRecordingStatus.RECEIVING,
        clock.instant().minus(Duration.ofMinutes(2)));
    String keyedKey = keyOf(session, VoiceMediaKind.RECORDING, keyed.getRecordingId());
    storeFile(session, VoiceMediaKind.RECORDING, keyed.getRecordingId(), "keyed");
    jdbc.update("update voice_recording set storage_key = ? where id = ?",
        keyedKey, keyed.getId());

    cleanupService.runCleanup();

    assertThat(recordings.findById(expired.getId())).isEmpty();
    assertThat(recordings.findById(unexpired.getId())).isPresent();
    assertThat(recordings.findById(keyed.getId())).isEmpty();
    assertThat(Files.exists(mediaRoot().resolve(keyedKey))).isFalse();
    // the null-key crash file is unreferenced by design — the orphan sweep reclaims it later
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, expired.getRecordingId())))).isTrue();
  }

  // ---------------------------------------------------------------- DISCARDED

  @Test
  void discardedRecordingDeletesFileAndRowAndToleratesMissingFiles() {
    var withFile = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, withFile.getRecordingId(), "audio");
    var withoutFile = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());

    cleanupService.runCleanup();

    assertThat(recordings.findById(withFile.getId())).isEmpty();
    assertThat(recordings.findById(withoutFile.getId())).isEmpty();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, withFile.getRecordingId())))).isFalse();
  }

  // ---------------------------------------------------------------- retention

  @Test
  void retentionDeletesAllRecordingsAndSpeechesOfExpiredCompletedSessions() throws Exception {
    var completed = seedSession(SessionStatus.COMPLETED);
    var attached = seedRecording(completed, VoiceRecordingStatus.ATTACHED, expiresFuture());
    storeFile(completed, VoiceMediaKind.RECORDING, attached.getRecordingId(), "attached");
    var discarded = seedRecording(completed, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(completed, VoiceMediaKind.RECORDING, discarded.getRecordingId(), "discarded");
    var readySpeech = seedSpeech(completed, QuestionSpeechStatus.READY, 1);
    storeFile(completed, VoiceMediaKind.SPEECH, readySpeech.getSpeechId(), "audio");
    var failedSpeech = seedSpeech(completed, QuestionSpeechStatus.FAILED, 2);

    clock.set(clock.instant().plus(Duration.ofDays(8)));
    cleanupService.runCleanup();

    assertThat(recordings.findById(attached.getId())).isEmpty();
    assertThat(recordings.findById(discarded.getId())).isEmpty();
    assertThat(speeches.findById(readySpeech.getId())).isEmpty();
    assertThat(speeches.findById(failedSpeech.getId())).isEmpty();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(completed, VoiceMediaKind.RECORDING, attached.getRecordingId())))).isFalse();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(completed, VoiceMediaKind.SPEECH, readySpeech.getSpeechId())))).isFalse();
  }

  @Test
  void retentionKeepsCompletedSessionsWithinTheRetentionWindow() throws Exception {
    var completed = seedSession(SessionStatus.COMPLETED);
    var attached = seedRecording(completed, VoiceRecordingStatus.ATTACHED, expiresFuture());
    storeFile(completed, VoiceMediaKind.RECORDING, attached.getRecordingId(), "attached");

    clock.set(clock.instant().plus(Duration.ofDays(1)));
    cleanupService.runCleanup();

    assertThat(recordings.findById(attached.getId())).isPresent();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(completed, VoiceMediaKind.RECORDING, attached.getRecordingId())))).isTrue();
  }

  @Test
  void retentionNeverTouchesLiveSessionsEvenWithAncientRows() throws Exception {
    var interviewingAttached = seedRecording(session, VoiceRecordingStatus.ATTACHED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, interviewingAttached.getRecordingId(), "attached");
    var interviewingSpeech = seedSpeech(session, QuestionSpeechStatus.READY, 2);
    storeFile(session, VoiceMediaKind.SPEECH, interviewingSpeech.getSpeechId(), "audio");
    var evaluating = seedSession(SessionStatus.EVALUATING);
    var evaluatingSpeech = seedSpeech(evaluating, QuestionSpeechStatus.READY, 1);
    storeFile(evaluating, VoiceMediaKind.SPEECH, evaluatingSpeech.getSpeechId(), "audio");

    clock.set(clock.instant().plus(Duration.ofDays(8)));
    cleanupService.runCleanup();

    assertThat(recordings.findById(interviewingAttached.getId())).isPresent();
    assertThat(speeches.findById(interviewingSpeech.getId())).isPresent();
    assertThat(speeches.findById(evaluatingSpeech.getId())).isPresent();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, interviewingAttached.getRecordingId())))).isTrue();
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.SPEECH, interviewingSpeech.getSpeechId())))).isTrue();
  }

  // ---------------------------------------------------------------- stuck task recovery

  @Test
  void stuckTranscribingRecordingIsTerminalizedAndTheUserRetryPathWorks() {
    var recording = seedRecording(session, VoiceRecordingStatus.TRANSCRIBING, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, recording.getRecordingId(), "media");
    seedTranscriptionTask(recording, AsyncTaskStatus.PUBLISHED);

    clock.set(clock.instant().plus(Duration.ofMinutes(31)));
    cleanupService.runCleanup();

    var failed = recordings.findById(recording.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    assertThat(transcriptionTaskOf(recording).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
    // markDead terminalizes without touching the media — the retry re-transcribes from it
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, recording.getRecordingId())))).isTrue();

    module.retry(currentUser, session.getSessionId(), recording.getRecordingId());

    var retried = recordings.findById(recording.getId()).orElseThrow();
    assertThat(retried.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(retried.getExecutionEpoch()).isEqualTo(1);
    assertThat(transcriptionTaskOf(recording).getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  @Test
  void stuckSynthesizingSpeechIsTerminalizedAndTheRetryPathWorks() {
    var speech = seedSpeech(session, QuestionSpeechStatus.SYNTHESIZING, 2);
    seedSynthesisTask(speech, AsyncTaskStatus.PUBLISHED);

    clock.set(clock.instant().plus(Duration.ofMinutes(31)));
    cleanupService.runCleanup();

    var failed = speeches.findById(speech.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(failed.getSafeError()).isEqualTo(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    assertThat(synthesisTaskOf(speech).getStatus()).isEqualTo(AsyncTaskStatus.DEAD);

    speechModule.retry(currentUser, session.getSessionId(), speech.getSpeechId());

    var retried = speeches.findById(speech.getId()).orElseThrow();
    assertThat(retried.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(synthesisTaskOf(speech).getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  @Test
  void freshPublishedTasksAreNotRecovered() {
    var recording = seedRecording(session, VoiceRecordingStatus.TRANSCRIBING, expiresFuture());
    seedTranscriptionTask(recording, AsyncTaskStatus.PUBLISHED);

    cleanupService.runCleanup(); // no clock advance: the task shows recent activity

    assertThat(recordings.findById(recording.getId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
  }

  // ---------------------------------------------------------------- orphan files

  @Test
  void orphanFileWithoutAnyRowIsDeletedOnlyAfterTheGracePeriod() throws Exception {
    String key = keyOf(session, VoiceMediaKind.RECORDING, UUID.randomUUID());
    Path file = plantFile(key, "orphan");

    cleanupService.runCleanup();
    assertThat(Files.exists(file)).isTrue(); // grace (24h) has not elapsed

    clock.set(clock.instant().plus(Duration.ofHours(25)));
    cleanupService.runCleanup();
    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void fileReferencedByItsRowIsNeverDeleted() {
    var speech = seedSpeech(session, QuestionSpeechStatus.READY, 2);
    storeFile(session, VoiceMediaKind.SPEECH, speech.getSpeechId(), "audio");
    String key = keyOf(session, VoiceMediaKind.SPEECH, speech.getSpeechId());

    clock.set(clock.instant().plus(Duration.ofHours(25)));
    cleanupService.runCleanup();

    assertThat(Files.exists(mediaRoot().resolve(key))).isTrue();
  }

  @Test
  void crashLeftFileOfAnExpiredNullKeyReceivingRowIsReclaimedAfterGrace() {
    var expired = seedRecording(session, VoiceRecordingStatus.RECEIVING,
        clock.instant().minus(Duration.ofMinutes(2)));
    storeFile(session, VoiceMediaKind.RECORDING, expired.getRecordingId(), "crash-left");
    cleanupService.runCleanup();
    Path crashFile = mediaRoot().resolve(keyOf(session, VoiceMediaKind.RECORDING, expired.getRecordingId()));
    assertThat(recordings.findById(expired.getId())).isEmpty();
    assertThat(Files.exists(crashFile)).isTrue(); // row gone, file orphaned

    clock.set(clock.instant().plus(Duration.ofHours(25)));
    cleanupService.runCleanup();
    assertThat(Files.exists(crashFile)).isFalse();
  }

  @Test
  void staleStoreStagingTempsAreReclaimedAfterGrace() throws Exception {
    Path dir = mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, UUID.randomUUID())).getParent();
    Files.createDirectories(dir);
    Path staging = dir.resolve(".media-123456789.tmp");
    Files.writeString(staging, "junk");

    clock.set(clock.instant().plus(Duration.ofHours(25)));
    cleanupService.runCleanup();

    assertThat(Files.exists(staging)).isFalse();
  }

  // ---------------------------------------------------------------- crash convergence

  @Test
  void convergesWhenTheFileStepCompletedBeforeTheRowDeletion() {
    var row = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    String key = keyOf(session, VoiceMediaKind.RECORDING, row.getRecordingId());
    storeFile(session, VoiceMediaKind.RECORDING, row.getRecordingId(), "audio");
    mediaStore.delete(key); // simulate the completed file step, crash before the row deletion

    cleanupService.runCleanup();

    assertThat(recordings.findById(row.getId())).isEmpty();
  }

  @Test
  void convergesAfterAFileDeletionFailureDeferredTheRow() {
    var row = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, row.getRecordingId(), "audio");
    doThrow(new VoiceMediaStorageException("simulated lock", new IOException("locked")))
        .doCallRealMethod().when(mediaStore).delete(anyString());

    cleanupService.runCleanup();
    assertThat(recordings.findById(row.getId())).isPresent(); // deferred with its file
    assertThat(Files.exists(mediaRoot().resolve(
        keyOf(session, VoiceMediaKind.RECORDING, row.getRecordingId())))).isTrue();

    cleanupService.runCleanup(); // the one-shot failure was consumed; the run converges
    assertThat(recordings.findById(row.getId())).isEmpty();
  }

  @Test
  void aSecondRunIsANoOp() {
    var row = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, row.getRecordingId(), "audio");
    clearInvocations(mediaStore);

    cleanupService.runCleanup();
    cleanupService.runCleanup();

    assertThat(recordings.findById(row.getId())).isEmpty();
    verify(mediaStore, times(1)).delete(anyString()); // exactly one file deletion total
  }

  // ---------------------------------------------------------------- batch claim

  @Test
  void cleanupIsSkippedWhileAnotherRunHoldsTheClaim() {
    var row = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, row.getRecordingId(), "audio");
    String token = claims.acquire(VoiceMediaCleanupService.RUN_CLAIM_KEY,
        Duration.ofMinutes(30)).orElseThrow();

    try {
      cleanupService.runCleanup();
      assertThat(recordings.findById(row.getId())).isPresent();
      assertThat(Files.exists(mediaRoot().resolve(
          keyOf(session, VoiceMediaKind.RECORDING, row.getRecordingId())))).isTrue();
    } finally {
      claims.release(VoiceMediaCleanupService.RUN_CLAIM_KEY, token);
    }

    cleanupService.runCleanup();
    assertThat(recordings.findById(row.getId())).isEmpty();
  }

  @Test
  void concurrentRunsDoNotDoubleDelete() throws Exception {
    List<VoiceRecordingEntity> rows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      var row = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
      storeFile(session, VoiceMediaKind.RECORDING, row.getRecordingId(), "audio-" + i);
      rows.add(row);
    }
    clearInvocations(mediaStore);
    var pool = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    var futures = new ArrayList<Future<?>>();
    for (int i = 0; i < 2; i++) {
      futures.add(pool.submit(() -> {
        barrier.await();
        cleanupService.runCleanup();
        return null;
      }));
    }
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    for (VoiceRecordingEntity row : rows) {
      assertThat(recordings.findById(row.getId())).isEmpty();
    }
    verify(mediaStore, times(rows.size())).delete(anyString()); // each file deleted exactly once
  }

  // ---------------------------------------------------------------- Windows sharing violation

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void lockedFileDefersTheRowAndTheBatchContinues() throws Exception {
    var lockedRow = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    String lockedKey = keyOf(session, VoiceMediaKind.RECORDING, lockedRow.getRecordingId());
    storeFile(session, VoiceMediaKind.RECORDING, lockedRow.getRecordingId(), "locked");
    var freeRow = seedRecording(session, VoiceRecordingStatus.DISCARDED, expiresFuture());
    storeFile(session, VoiceMediaKind.RECORDING, freeRow.getRecordingId(), "free");

    try (var open = new FileInputStream(mediaRoot().resolve(lockedKey).toFile())) {
      cleanupService.runCleanup();
    }
    assertThat(recordings.findById(lockedRow.getId())).isPresent();
    assertThat(Files.exists(mediaRoot().resolve(lockedKey))).isTrue();
    assertThat(recordings.findById(freeRow.getId())).isEmpty(); // the batch continued

    cleanupService.runCleanup();
    assertThat(recordings.findById(lockedRow.getId())).isEmpty();
  }

  // ---------------------------------------------------------------- seeding helpers

  private InterviewSessionEntity seedSession(SessionStatus target) throws Exception {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java 后端工程师",
        "负责 Spring Boot 微服务开发，熟悉 MySQL 与 Redis",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);
    var seeded = sessions.save(InterviewSessionEntity.preparing(
        user.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端工程师", "dashscope", "qwen",
        json.writeValueAsString(brief), null, InterviewMode.VOICE, VOICE_SNAPSHOT));
    seeded.preparationReady();
    seeded.beginFixedInterview();
    if (target == SessionStatus.EVALUATING) {
      seeded.beginEvaluation();
    } else if (target == SessionStatus.COMPLETED) {
      seeded.beginEvaluation();
      seeded.completeEvaluation(); // completed_at = real now; the mutable clock moves forward
    }
    return sessions.saveAndFlush(seeded);
  }

  private InterviewTurnEntity seedTurn(InterviewSessionEntity session, int turnNo) {
    var existing = turns.findBySessionIdAndTurnNo(session.getId(), turnNo);
    if (existing.isPresent()) {
      return existing.get(); // uq_question_card_phase_sequence forbids a second card per turn
    }
    var card = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, turnNo, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    return turns.save(InterviewTurnEntity.asked(
        session.getId(), turnNo, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), "请自我介绍"));
  }

  private VoiceRecordingEntity seedRecording(
      InterviewSessionEntity session, VoiceRecordingStatus target, Instant expiresAt) {
    var row = recordings.save(VoiceRecordingEntity.receiving(
        user.getId(), UUID.randomUUID(), UUID.randomUUID(),
        session.getId(), turn(session).getId(), expiresAt));
    String key = keyOf(session, VoiceMediaKind.RECORDING, row.getRecordingId());
    switch (target) {
      case RECEIVING -> { /* phase-1 residue: no media yet */ }
      case DISCARDED -> {
        row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
        row.failTranscription(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
        row.discard();
      }
      case UPLOADED -> row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
      case TRANSCRIBING -> {
        row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
        row.startTranscription();
      }
      case READY -> {
        row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
        row.startTranscription();
        row.completeTranscription("dashscope", "model", "req", "你好", 1L);
      }
      case ATTACHED -> {
        row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
        row.startTranscription();
        row.completeTranscription("dashscope", "model", "req", "你好", 1L);
        row.attach(UUID.randomUUID());
      }
      case FAILED -> {
        row.acceptUpload(key, "audio/webm", 1, 1000L, "0".repeat(64));
        row.failTranscription(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
      }
    }
    return recordings.saveAndFlush(row);
  }

  private QuestionSpeechEntity seedSpeech(
      InterviewSessionEntity session, QuestionSpeechStatus target, int turnNo) {
    var speechTurn = seedTurn(session, turnNo);
    var row = speeches.save(QuestionSpeechEntity.pending(
        user.getId(), UUID.randomUUID(), session.getId(), speechTurn.getId(),
        QuestionSpeechHashes.of("问题文本"), "dashscope", "cosyvoice-v3-flash", "longanyang"));
    String key = keyOf(session, VoiceMediaKind.SPEECH, row.getSpeechId());
    switch (target) {
      case PENDING -> { /* nothing scheduled yet */ }
      case SYNTHESIZING -> row.startSynthesis();
      case READY -> {
        row.startSynthesis();
        row.completeSynthesis("req", key, "audio/mpeg", 1L, 1000L);
      }
      case FAILED -> row.failSynthesis(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    }
    return speeches.saveAndFlush(row);
  }

  private AsyncTaskEntity seedTranscriptionTask(VoiceRecordingEntity recording, AsyncTaskStatus status) {
    var task = tasks.save(AsyncTaskEntity.pending(
        user.getId(), AsyncTaskType.VOICE_TRANSCRIPTION,
        VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recording.getRecordingId(),
        "{\"recordingId\":\"" + recording.getRecordingId() + "\"}"));
    task.setStatus(status);
    return tasks.saveAndFlush(task);
  }

  private AsyncTaskEntity seedSynthesisTask(QuestionSpeechEntity speech, AsyncTaskStatus status) {
    var task = tasks.save(AsyncTaskEntity.pending(
        user.getId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(),
        "{\"speechId\":\"" + speech.getSpeechId() + "\"}"));
    task.setStatus(status);
    return tasks.saveAndFlush(task);
  }

  private AsyncTaskEntity transcriptionTaskOf(VoiceRecordingEntity recording) {
    return tasks.findByTaskTypeAndBizKeyAndUserAccountId(
            AsyncTaskType.VOICE_TRANSCRIPTION,
            VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recording.getRecordingId(),
            user.getId())
        .orElseThrow();
  }

  private AsyncTaskEntity synthesisTaskOf(QuestionSpeechEntity speech) {
    return tasks.findByTaskTypeAndBizKeyAndUserAccountId(
            AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
            QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId(),
            user.getId())
        .orElseThrow();
  }

  private InterviewTurnEntity turn(InterviewSessionEntity session) {
    // a freshly seeded session (retention tests) has no turn yet — create the first one
    return turns.findBySessionIdAndTurnNo(session.getId(), 1)
        .orElseGet(() -> seedTurn(session, 1));
  }

  private Instant expiresFuture() {
    return clock.instant().plus(Duration.ofMinutes(10));
  }

  private String keyOf(InterviewSessionEntity session, VoiceMediaKind kind, UUID resourceId) {
    return new VoiceMediaKey(user.getUserId(), session.getId(), kind, resourceId).storageKey();
  }

  private void storeFile(InterviewSessionEntity session, VoiceMediaKind kind,
      UUID resourceId, String content) {
    mediaStore.store(
        new VoiceMediaKey(user.getUserId(), session.getId(), kind, resourceId),
        new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
        voice.maxUploadBytes());
  }

  private Path plantFile(String key, String content) throws IOException {
    Path file = mediaRoot().resolve(key);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  private Path mediaRoot() {
    return voice.filesRoot().toAbsolutePath().normalize();
  }

  private void wipeMediaRoot() throws IOException {
    Path root = mediaRoot();
    if (!Files.exists(root)) {
      return;
    }
    List<Path> all;
    try (var walk = Files.walk(root)) {
      // keep the root itself: the store holds its real path and re-verifies it on every call
      all = walk.filter(path -> !path.equals(root))
          .sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList();
    }
    for (Path path : all) {
      Files.deleteIfExists(path);
    }
  }

  @TestConfiguration
  static class CleanupClockConfig {
    @Bean
    @Primary
    MutableClock cleanupClock() {
      return new MutableClock(Instant.now());
    }
  }

  /** Test clock: starts at real now (matching {@code @CreationTimestamp} / completed_at), advances on demand. */
  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant value) {
      this.now = value;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
