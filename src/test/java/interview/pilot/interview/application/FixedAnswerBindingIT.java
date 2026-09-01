package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessagePublisher;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Answer submission binding against MySQL (real repositories, V23 schema). A VOICE
 * submission binds its READY recording inside the same claim transaction that persists the
 * attempt and advances the turn; replays and cross-resource rejections keep the recording
 * untouched.
 */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/fixed-answer-binding-it-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-binding-it",
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
class FixedAnswerBindingIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_answer_binding");

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
  private FollowUpGenerator followUps;

  @Autowired
  private FixedAnswerService answers;

  @MockitoSpyBean
  private VoiceRecordingRepository recordings;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private InterviewSessionRepository sessions;

  @Autowired
  private InterviewTurnRepository turns;

  @Autowired
  private InterviewQuestionCardRepository cards;

  @Autowired
  private AnswerAttemptRepository attempts;

  @Autowired
  private UserAccountRepository users;

  private CurrentUser user;
  private UserAccountEntity account;
  private InterviewSessionEntity session;
  private InterviewTurnEntity turn;
  private InterviewQuestionCardEntity fundamentalsCard;

  @BeforeEach
  void setUp() {
    when(claims.acquire(anyString(), any())).thenReturn(Optional.of("token"));
    when(claims.release(anyString(), anyString())).thenReturn(true);
    when(followUps.generate(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("如果本轮的追问超时了，你会怎么处理并说明理由？");
    attempts.deleteAll();
    recordings.deleteAll();
    turns.deleteAll();
    cards.deleteAll();
    sessions.deleteAll();
    users.deleteAll();
    account = users.save(UserAccountEntity.register(
        "binding-it@example.com", "hash", "Binding IT"));
    user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    session = seedSession(account, InterviewMode.VOICE);
    turn = seedTurn(session, 1);
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void textSubmissionPersistsTheSubmissionFingerprintAndMarksTheTurnText() {
    var request = new SubmitAnswerRequest(UUID.randomUUID(), "  有效回答  ");

    var result = submit(request);

    assertThat(result.idempotentReplay()).isFalse();
    var stored = attempts.findByRequestId(request.requestId()).orElseThrow();
    assertThat(stored.getSubmissionFingerprint())
        .isEqualTo(SubmissionFingerprint.of("有效回答", InputMode.TEXT, null));
    assertThat(turns.findById(turn.getId()).orElseThrow().getInputMode())
        .isEqualTo(InputMode.TEXT);
    assertThat(turns.findById(turn.getId()).orElseThrow().getAnswerText())
        .isEqualTo("有效回答");
  }

  @Test
  void voiceSubmissionBindsTheReadyRecordingAndMarksTheTurnVoice() {
    var recording = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, recording.getRecordingId());

    var result = submit(request);

    assertThat(result.idempotentReplay()).isFalse();
    var bound = recordings.findByRecordingId(recording.getRecordingId()).orElseThrow();
    assertThat(bound.getStatus()).isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(bound.getAttachedAnswerRequestId()).isEqualTo(request.requestId());
    var stored = attempts.findByRequestId(request.requestId()).orElseThrow();
    assertThat(stored.getSubmissionFingerprint()).isEqualTo(
        SubmissionFingerprint.of("转写确认", InputMode.VOICE, recording.getRecordingId()));
    assertThat(turns.findById(turn.getId()).orElseThrow().getInputMode())
        .isEqualTo(InputMode.VOICE);
  }

  // ---------------------------------------------------------------- idempotency

  @Test
  void sameRequestIdReplaysAndKeepsTheRecordingAttachedToTheFirstSubmission() {
    var recording = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, recording.getRecordingId());
    submit(request);

    var replay = answers.claim(user, session.getSessionId(), request);

    assertThat(replay.owner()).isFalse();
    assertThat(replay.replay()).isNotNull();
    assertThat(answers.process(replay).idempotentReplay()).isTrue();
    var bound = recordings.findByRecordingId(recording.getRecordingId()).orElseThrow();
    assertThat(bound.getStatus()).isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(bound.getAttachedAnswerRequestId()).isEqualTo(request.requestId());
    assertThat(attempts.count()).isEqualTo(1);
  }

  @Test
  void sameRequestIdWithEditedTranscriptTextIsAStableConflict() {
    var recording = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, recording.getRecordingId());
    submit(request);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(request.requestId(), "转写确认（编辑过）",
            InputMode.VOICE, recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo("REQUEST_ID_CONFLICT");
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    var bound = recordings.findByRecordingId(recording.getRecordingId()).orElseThrow();
    assertThat(bound.getAttachedAnswerRequestId()).isEqualTo(request.requestId());
    assertThat(attempts.count()).isEqualTo(1);
  }

  @Test
  void sameRequestIdWithADifferentRecordingIsAStableConflict() {
    var first = readyRecording(account, session, turn);
    var second = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, first.getRecordingId());
    submit(request);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(request.requestId(), "转写确认", InputMode.VOICE,
            second.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo("REQUEST_ID_CONFLICT"));
    assertThat(recordings.findByRecordingId(second.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.READY);
    assertThat(recordings.findByRecordingId(first.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(attempts.count()).isEqualTo(1);
  }

  @Test
  void replayOfAFailedAttemptReturnsAStableFailedCode() {
    var recording = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, recording.getRecordingId());
    answers.claim(user, session.getSessionId(), request);
    var attempt = attempts.findByRequestId(request.requestId()).orElseThrow();
    attempt.fail("ANSWER_PROCESSING_FAILED");
    attempts.saveAndFlush(attempt);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(), request))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo("ANSWER_FAILED");
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    // The recording stays bound to the failed attempt: a retry must use a new requestId
    // (and re-record or fall back to text) instead of rebinding.
    assertThat(recordings.findByRecordingId(recording.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(attempts.count()).isEqualTo(1);
  }

  @Test
  void replayOfAVoiceSubmissionAfterSessionCompletionReturnsTheStoredResultWithoutRebinding() {
    var recording = readyRecording(account, session, turn);
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, recording.getRecordingId());
    var result = submit(request);
    // Reload: the processed submission advanced the session (version bump) — the detached
    // fixture reference is stale.
    var evaluating = sessions.findById(session.getId()).orElseThrow();
    evaluating.beginEvaluation();
    sessions.saveAndFlush(evaluating);

    var replay = answers.process(answers.claim(user, session.getSessionId(), request));

    assertThat(replay.idempotentReplay()).isTrue();
    assertThat(replay.completedTurnNo()).isEqualTo(result.completedTurnNo());
    assertThat(replay.sessionStatus()).isEqualTo(result.sessionStatus());
    var bound = recordings.findByRecordingId(recording.getRecordingId()).orElseThrow();
    assertThat(bound.getStatus()).isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(bound.getAttachedAnswerRequestId()).isEqualTo(request.requestId());
    assertThat(attempts.count()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- rejections

  @Test
  void aNonReadyRecordingRejectsTheSubmission() {
    var recording = uploadedRecording(account, session, turn);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_READY);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(recordings.findByRecordingId(recording.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(attempts.count()).isZero();
    assertThat(turns.findById(turn.getId()).orElseThrow().getStatus())
        .isEqualTo(interview.pilot.interview.domain.TurnStatus.ASKED);
  }

  @Test
  void anAlreadyAttachedRecordingRejectsTheSubmission() {
    var recording = readyRecording(account, session, turn);
    recording.attach(UUID.randomUUID());
    recordings.saveAndFlush(recording);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(attempts.count()).isZero();
  }

  @Test
  void recordingIdWithTextInputModeIsAStableConflict() {
    var recording = readyRecording(account, session, turn);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "文字回退", InputMode.TEXT,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(recordings.findByRecordingId(recording.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.READY);
    assertThat(attempts.count()).isZero();
  }

  @Test
  void voiceInputModeWithoutARecordingIdIsAStableConflict() {
    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE, null)))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(attempts.count()).isZero();
    assertThat(turns.findById(turn.getId()).orElseThrow().getStatus())
        .isEqualTo(interview.pilot.interview.domain.TurnStatus.ASKED);
  }

  @Test
  void recordingIdOnATextSessionIsAStableConflict() {
    var textSession = seedSession(account, InterviewMode.TEXT);
    var textTurn = seedTurn(textSession, 1);

    assertThatThrownBy(() -> answers.claim(user, textSession.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            readyRecording(account, textSession, textTurn).getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH));
  }

  @Test
  void crossUserRecordingIsHiddenAsNotFound() {
    var other = users.save(UserAccountEntity.register(
        "binding-other@example.com", "hash", "Other"));
    var otherSession = seedSession(other, InterviewMode.VOICE);
    var otherTurn = seedTurn(otherSession, 1);
    var recording = readyRecording(other, otherSession, otherTurn);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND);
          assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });
  }

  @Test
  void crossSessionRecordingIsHiddenAsNotFound() {
    var otherSession = seedSession(account, InterviewMode.VOICE);
    var otherTurn = seedTurn(otherSession, 1);
    var recording = readyRecording(account, otherSession, otherTurn);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND));
  }

  @Test
  void crossTurnRecordingIsHiddenAsNotFound() {
    var recording = readyRecording(account, session, turn);
    var nextTurn = turns.save(InterviewTurnEntity.asked(
        session.getId(), 2, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN,
        fundamentalsCard.getId(), "请解释并发问题"));
    session.advanceTo(2, QuestionType.MAIN);
    sessions.saveAndFlush(session);

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND));
    assertThat(recordings.findByRecordingId(recording.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.READY);
  }

  @Test
  void aSecondSubmissionOnTheClaimedTurnIsRejectedAndLeavesTheOtherRecordingReady() {
    var first = readyRecording(account, session, turn);
    var second = readyRecording(account, session, turn);
    answers.claim(user, session.getSessionId(), new SubmitAnswerRequest(
        UUID.randomUUID(), "转写确认", InputMode.VOICE, first.getRecordingId()));

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(UUID.randomUUID(), "另一段转写", InputMode.VOICE,
            second.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code()).isEqualTo("TURN_ALREADY_CLAIMED"));
    assertThat(recordings.findByRecordingId(first.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(recordings.findByRecordingId(second.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.READY);
    assertThat(attempts.count()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- concurrency

  @Test
  void concurrentClaimsWithDifferentRecordingsBindExactlyOne() throws Exception {
    var first = readyRecording(account, session, turn);
    var second = readyRecording(account, session, turn);
    var barrier = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    List<Object> outcomes = new CopyOnWriteArrayList<>();
    List<UUID> requestIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    List<VoiceRecordingEntity> candidates = List.of(first, second);
    try {
      for (int i = 0; i < 2; i++) {
        int index = i;
        executor.execute(() -> {
          try {
            barrier.await(10, TimeUnit.SECONDS);
            outcomes.add(answers.claim(user, session.getSessionId(),
                new SubmitAnswerRequest(requestIds.get(index), "转写确认", InputMode.VOICE,
                    candidates.get(index).getRecordingId())));
          } catch (Exception exception) {
            outcomes.add(exception);
          }
        });
      }
      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(outcomes)
        .filteredOn(outcome -> outcome instanceof FixedAnswerClaim)
        .extracting(outcome -> ((FixedAnswerClaim) outcome).owner())
        .containsExactly(true);
    assertThat(outcomes)
        .describedAs("all outcomes: %s", outcomes.stream()
            .map(outcome -> outcome instanceof Throwable
                ? outcome.getClass().getName() + ": " + ((Throwable) outcome).getMessage()
                : outcome.toString())
            .toList())
        .filteredOn(outcome -> outcome instanceof BusinessException)
        .extracting(outcome -> ((BusinessException) outcome).code())
        .containsExactly("TURN_ALREADY_CLAIMED");
    assertThat(recordings.findAllBySessionIdAndTurnIdOrderByCreatedAt(
        session.getId(), turn.getId()))
        .extracting(VoiceRecordingEntity::getStatus)
        .containsExactlyInAnyOrder(VoiceRecordingStatus.ATTACHED, VoiceRecordingStatus.READY);
    assertThat(attempts.count()).isEqualTo(1);
  }

  @Test
  void aRecordingDiscardedConcurrentlyWithTheClaimSurfacesTheAccurateStateOnRetry()
      throws Exception {
    var recording = readyRecording(account, session, turn);
    var answerRequestId = UUID.randomUUID();
    AtomicInteger reads = new AtomicInteger();
    // The first recording read loads the stale READY snapshot through the claim
    // transaction's persistence context while a concurrent discard commits a version bump in
    // its own transaction: the claim's optimistic flush then loses and the retry must
    // re-read the row instead of mislabeling the loss as TURN_ALREADY_CLAIMED.
    doAnswer(invocation -> {
      var stale = entityManager.find(VoiceRecordingEntity.class, recording.getId());
      if (reads.getAndIncrement() == 0) {
        var discarder = Executors.newSingleThreadExecutor();
        discarder.execute(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> {
              var row = recordings.findById(recording.getId()).orElseThrow();
              row.discard();
              recordings.saveAndFlush(row);
            }));
        discarder.shutdown();
        assertThat(discarder.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      }
      return Optional.ofNullable(stale);
    }).when(recordings).findByRecordingId(recording.getRecordingId());

    assertThatThrownBy(() -> answers.claim(user, session.getSessionId(),
        new SubmitAnswerRequest(answerRequestId, "转写确认", InputMode.VOICE,
            recording.getRecordingId())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo(VoiceErrorCodes.VOICE_RECORDING_NOT_READY);
          assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(recordings.findByRecordingId(recording.getRecordingId()).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.DISCARDED);
    assertThat(attempts.count()).isZero();
    assertThat(turns.findById(turn.getId()).orElseThrow().getStatus())
        .isEqualTo(interview.pilot.interview.domain.TurnStatus.ASKED);
  }

  // ---------------------------------------------------------------- helpers

  private FixedAnswerResult submit(SubmitAnswerRequest request) {
    return answers.process(answers.claim(user, session.getSessionId(), request));
  }

  private VoiceRecordingEntity readyRecording(
      UserAccountEntity owner, InterviewSessionEntity forSession, InterviewTurnEntity forTurn) {
    var recording = recordings.save(VoiceRecordingEntity.receiving(
        owner.getId(), UUID.randomUUID(), UUID.randomUUID(), forSession.getId(), forTurn.getId(),
        Instant.now().plus(Duration.ofDays(1))));
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "digest");
    recording.startTranscription();
    recording.completeTranscription("dashscope", "m", "r", "转写结果", 100L);
    return recordings.saveAndFlush(recording);
  }

  private VoiceRecordingEntity uploadedRecording(
      UserAccountEntity owner, InterviewSessionEntity forSession, InterviewTurnEntity forTurn) {
    var recording = recordings.save(VoiceRecordingEntity.receiving(
        owner.getId(), UUID.randomUUID(), UUID.randomUUID(), forSession.getId(), forTurn.getId(),
        Instant.now().plus(Duration.ofDays(1))));
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "digest");
    return recordings.saveAndFlush(recording);
  }

  private InterviewSessionEntity seedSession(UserAccountEntity owner, InterviewMode mode) {
    var session = sessions.save(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen-plus",
        "{}", null, mode,
        mode == InterviewMode.VOICE
            ? "{\"schemaVersion\":1,\"asrProvider\":\"dashscope\",\"asrModel\":\"fun-asr-flash-2026-06-15\","
                + "\"ttsProvider\":\"unconfigured\",\"ttsModel\":\"unconfigured\",\"voice\":\"server-default\","
                + "\"maxRecordingSeconds\":300,\"maxUploadBytes\":8388608}"
            : null));
    session.preparationReady();
    session.beginFixedInterview();
    return sessions.saveAndFlush(session);
  }

  private InterviewTurnEntity seedTurn(InterviewSessionEntity session, int turnNo) {
    var card = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "请自我介绍",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 0, null));
    fundamentalsCard = cards.save(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.FUNDAMENTALS, 1, "并发", "请解释并发问题",
        "[]", GroundingMode.GENERAL, RagStatus.DISABLED, "{}", "[]", 1, null));
    return turns.save(InterviewTurnEntity.asked(
        session.getId(), turnNo, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), "请自我介绍"));
  }
}
