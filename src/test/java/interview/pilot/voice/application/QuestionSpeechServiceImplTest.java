package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * Race and defensive-edge unit tests for {@link QuestionSpeechServiceImpl} with repository
 * doubles (the happy paths live in the MySQL-backed {@link QuestionSpeechModuleTest}): the
 * lost getOrSchedule insert race retries once, the retry optimistic-lock conflict retries
 * once, and a defensively recreated task row joins the speech's exact fenced epoch.
 */
class QuestionSpeechServiceImplTest {

  private QuestionSpeechRepository speeches;
  private InterviewSessionRepository sessions;
  private InterviewTurnRepository turns;
  private AsyncTaskRepository tasks;
  private ProcessingClaim claims;
  private VoiceMediaStore mediaStore;
  private QuestionSpeechTaskCreator questionSpeechTaskCreator;
  private QuestionSpeechServiceImpl module;

  private final CurrentUser user =
      new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");
  private static final long SESSION_DB_ID = 7L;
  private static final long TURN_DB_ID = 9L;
  private final InterviewSessionEntity session = seedSession();
  private final InterviewTurnEntity turn =
      InterviewTurnEntity.asked(SESSION_DB_ID, 1, InterviewPhase.SELF_INTRODUCTION,
          QuestionType.SELF_INTRODUCTION, TURN_DB_ID, "请自我介绍");
  private final UUID speechUuid = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    // The entities are factory-built but never persisted: pin their database ids so the
    // repository stubs and the cross-session ownership checks see a consistent world.
    ReflectionTestUtils.setField(session, "id", SESSION_DB_ID);
    ReflectionTestUtils.setField(turn, "id", TURN_DB_ID);
    speeches = mock(QuestionSpeechRepository.class);
    sessions = mock(InterviewSessionRepository.class);
    turns = mock(InterviewTurnRepository.class);
    tasks = mock(AsyncTaskRepository.class);
    claims = mock(ProcessingClaim.class);
    mediaStore = mock(VoiceMediaStore.class);
    questionSpeechTaskCreator = mock(QuestionSpeechTaskCreator.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    module = new QuestionSpeechServiceImpl(
        speeches, sessions, turns, tasks, claims, mediaStore,
        questionSpeechTaskCreator, transactionManager);
    when(claims.clearTerminal(anyString())).thenReturn(ProcessingClaim.ClearResult.CLEARED);
    when(sessions.findBySessionIdAndUserAccountId(session.getSessionId(), 1L))
        .thenReturn(Optional.of(session));
    when(turns.findBySessionIdAndTurnNo(SESSION_DB_ID, 1)).thenReturn(Optional.of(turn));
    when(speeches.findByTurnId(any())).thenReturn(Optional.empty());
  }

  @Test
  void getOrScheduleRetriesOnceWhenTheInsertRaceIsLost() {
    var speech = QuestionSpeechEntity.pending(
        1L, speechUuid, SESSION_DB_ID, TURN_DB_ID, "abc123",
        "dashscope", "cosyvoice-v3-flash", "longanyang");
    when(questionSpeechTaskCreator.createForTurn(session, turn))
        .thenThrow(new DataIntegrityViolationException("uq_question_speech_turn"))
        .thenReturn(speechUuid);
    when(speeches.findBySpeechId(speechUuid)).thenReturn(Optional.of(speech));

    var view = module.getOrSchedule(user, session.getSessionId(), 1);

    assertThat(view.speechId()).isEqualTo(speechUuid);
    assertThat(view.status()).isEqualTo(QuestionSpeechViewStatus.PENDING);
    // The retried transaction re-reads the committed winner instead of failing the request.
    verify(questionSpeechTaskCreator, times(2)).createForTurn(session, turn);
  }

  @Test
  void retryRetriesOnceOnAnOptimisticLockConflict() {
    var speech = QuestionSpeechEntity.pending(
        1L, speechUuid, SESSION_DB_ID, TURN_DB_ID, "abc123",
        "dashscope", "cosyvoice-v3-flash", "longanyang");
    ReflectionTestUtils.setField(speech, "id", 42L);
    speech.moveTo(QuestionSpeechStatus.FAILED);
    var task = AsyncTaskEntity.pending(
        1L, AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechUuid,
        "{\"speechId\":\"" + speechUuid + "\"}");
    when(speeches.findBySpeechId(speechUuid)).thenReturn(Optional.of(speech));
    when(speeches.findById(anyLong())).thenReturn(Optional.of(speech));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechUuid, 1L))
        .thenReturn(Optional.of(task));
    // The mocked transaction manager does not roll the shared entity back on failure, so the
    // first flush restores the pre-transaction state itself — the faithful database behavior.
    doAnswer(invocation -> {
      ReflectionTestUtils.setField(speech, "status", QuestionSpeechStatus.FAILED);
      ReflectionTestUtils.setField(speech, "executionEpoch", 0L);
      throw new OptimisticLockingFailureException("row changed");
    }).doNothing().when(speeches).flush();

    module.retry(user, session.getSessionId(), speechUuid);

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(speech.getExecutionEpoch()).isEqualTo(1);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getExecutionEpoch()).isEqualTo(1);
    // The claim clear runs before every transaction attempt, mirroring the recording module.
    verify(claims, times(2)).clearTerminal(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechUuid);
  }

  @Test
  void retryRecreatesAMissingTaskRowAtTheSpeechEpoch() {
    // A speech already retried once (epoch 1) fails again; this time the task row is missing
    // (defensive edge — the unique key normally guarantees one row per speech).
    var speech = QuestionSpeechEntity.pending(
        1L, speechUuid, SESSION_DB_ID, TURN_DB_ID, "abc123",
        "dashscope", "cosyvoice-v3-flash", "longanyang");
    ReflectionTestUtils.setField(speech, "id", 42L);
    speech.moveTo(QuestionSpeechStatus.FAILED);
    speech.beginRetry(); // FAILED → PENDING, epoch 1
    speech.startSynthesis();
    speech.failSynthesis("VOICE_QUESTION_SPEECH_FAILED");
    assertThat(speech.getExecutionEpoch()).isEqualTo(1);
    when(speeches.findBySpeechId(speechUuid)).thenReturn(Optional.of(speech));
    when(speeches.findById(anyLong())).thenReturn(Optional.of(speech));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechUuid, 1L))
        .thenReturn(Optional.empty());
    when(tasks.save(Mockito.any(AsyncTaskEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    module.retry(user, session.getSessionId(), speechUuid);

    // The speech moved to its second generation (epoch 2); a recreated task row must join
    // that exact generation — a row starting at epoch 0 (+1 → 1) would break the lockstep
    // and a current-generation dead-letter could never pass markDead's epoch triple-check.
    assertThat(speech.getExecutionEpoch()).isEqualTo(2);
    AsyncTaskEntity recreated = Mockito.mockingDetails(tasks).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("save"))
        .findFirst().orElseThrow().getArgument(0);
    assertThat(recreated.getExecutionEpoch()).isEqualTo(2);
    assertThat(recreated.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  private static InterviewSessionEntity seedSession() {
    return InterviewSessionEntity.preparing(
        1L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen-plus",
        "{}", null, InterviewMode.VOICE, "{}");
  }
}
