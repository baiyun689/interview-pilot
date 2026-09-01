package interview.pilot.voice.application;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * Question speech synthesis half of the voice interview (plan §11), driven by the
 * {@code QUESTION_SPEECH_SYNTHESIS} listener:
 *
 * <ol>
 *   <li>a short transaction validates the message identity, the task/speech epoch pair, the
 *       speech state (PENDING, or SYNTHESIZING left by a crash-recovery duplicate) and the
 *       turn text against the row's {@code text_sha256} (the question text is immutable — a
 *       drift guard), moves the speech to SYNTHESIZING and the task to PUBLISHED, and
 *       captures the question text for the provider call;</li>
 *   <li>outside any transaction the {@link SpeechSynthesizer} is called and the returned
 *       audio is streamed into the store (probe + whitelist + size bound via the 3-arg
 *       {@link VoiceMediaStore#store(VoiceMediaKey, java.io.InputStream, long)} variant). The
 *       voice profile comes from the ROW's pinned provider/model/voice columns (captured at
 *       creation), never from the live configuration — a redeploy with changed (or removed)
 *       TTS config must not re-synthesize a retried speech with a different voice than the
 *       row and session snapshot record;</li>
 *   <li>a final short transaction re-checks the epoch, the SYNTHESIZING state and the
 *       text_sha256 (stale messages lose), persists storageKey/contentType/size/duration/
 *       providerRequestId and moves the speech to READY.</li>
 * </ol>
 *
 * <p>Failure taxonomy: operational failures (network/429/5xx/timeout, media IO) throw
 * {@link SpeechSynthesisRetryableException} so the listener re-queues via the delayed-retry
 * pipeline; deterministic failures (4xx rejection, empty or unsupported audio, text drift)
 * and retry exhaustion move the speech to FAILED with a safe_error and make the task
 * terminal. A synthesis failure NEVER rolls back or terminates the interview: this handler
 * only reads the turn/session rows (the question text) and only mutates the question_speech
 * and async_task rows. Manual retry is Task 8's speech retry endpoint — this handler never
 * resets tasks.
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceSynthesisHandler {

  static final String EMPTY_AUDIO_ERROR = "The TTS provider returned no audio";
  static final String UNSUPPORTED_AUDIO_ERROR = "The TTS provider returned unsupported audio";
  static final String TEXT_MISMATCH_ERROR = "The question speech text no longer matches the turn question";
  static final String MISSING_TURN_ERROR = "The question speech turn is missing";
  static final String RETRYABLE_ERROR = "Voice synthesis temporarily unavailable";

  private final QuestionSpeechRepository speeches;
  private final InterviewTurnRepository turns;
  private final UserAccountRepository users;
  private final AsyncTaskRepository tasks;
  private final SpeechSynthesizer synthesizer;
  private final VoiceMediaStore mediaStore;
  private final VoiceProperties properties;
  private final AiMetrics metrics;
  private final TransactionTemplate transactions;

  public VoiceSynthesisHandler(
      QuestionSpeechRepository speeches,
      InterviewTurnRepository turns,
      UserAccountRepository users,
      AsyncTaskRepository tasks,
      SpeechSynthesizer synthesizer,
      VoiceMediaStore mediaStore,
      VoiceProperties properties,
      AiMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.speeches = speeches;
    this.turns = turns;
    this.users = users;
    this.tasks = tasks;
    this.synthesizer = synthesizer;
    this.mediaStore = mediaStore;
    this.properties = properties;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public VoiceSynthesisTarget inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  private VoiceSynthesisTarget inspectInTransaction(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    QuestionSpeechEntity speech = requireSpeech(task);
    if (message.executionEpoch() < task.getExecutionEpoch()) {
      // A stale generation must never touch the row: the listener treats this as terminal.
      return new VoiceSynthesisTarget(
          speech.getSpeechId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    if (message.executionEpoch() != task.getExecutionEpoch()) {
      throw new IllegalArgumentException("Voice synthesis message epoch is invalid");
    }
    boolean taskTerminal = task.getStatus() == AsyncTaskStatus.COMPLETED
        || task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    // A terminal speech is any status the claim transaction cannot act on: READY/FAILED are
    // the direct outcomes, PENDING is the only actionable pre-claim state and SYNTHESIZING is
    // in-flight. Without this, a duplicate delivery arriving after READY would dead-letter
    // and requeue forever (begin refuses, markDead refuses → IllegalStateException → requeue).
    boolean speechTerminal = speech.getStatus() == QuestionSpeechStatus.READY
        || speech.getStatus() == QuestionSpeechStatus.FAILED;
    return new VoiceSynthesisTarget(
        speech.getSpeechId(), taskTerminal && speechTerminal,
        task.getAttemptCount(), task.getExecutionEpoch());
  }

  public Outcome synthesize(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> begin(message));
    if (begin.stale()) {
      return Outcome.STALE;
    }
    if (begin.terminal()) {
      return Outcome.TERMINAL;
    }
    return synthesizeMedia(begin.work());
  }

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      QuestionSpeechEntity speech = requireSpeech(task);
      return markDead(task, speech, attemptGeneration, message.executionEpoch());
    }));
  }

  public boolean markDeadCurrent(TaskMessage message) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      QuestionSpeechEntity speech = requireSpeech(task);
      return markDead(task, speech, task.getAttemptCount(), message.executionEpoch());
    }));
  }

  /**
   * Claim path (steps 1): validates again after the Redis claim was acquired, moves the
   * speech PENDING → SYNTHESIZING (or reuses the SYNTHESIZING state left by a crash-recovery
   * duplicate) without touching the epoch, marks the task PUBLISHED and captures the question
   * text. A text_sha256 drift — impossible through the normal flow since the question text is
   * immutable, but guarded anyway — is a deterministic failure resolved in this same
   * transaction so the row cannot stick in SYNTHESIZING.
   */
  private BeginResult begin(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    QuestionSpeechEntity speech = requireSpeech(task);
    if (task.getExecutionEpoch() != message.executionEpoch()) {
      return new BeginResult(null, true, false); // a newer generation owns the row
    }
    switch (speech.getStatus()) {
      case PENDING -> speech.startSynthesis();
      case SYNTHESIZING -> { /* crash-recovery duplicate; the epoch fence protects */ }
      default -> throw new IllegalStateException("Question speech is not synthesis-ready");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Voice synthesis task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    int attemptGeneration = task.getAttemptCount();
    task.setLastError(null);
    InterviewTurnEntity turn = turns.findById(speech.getTurnId())
        .orElseThrow(() -> new IllegalStateException(MISSING_TURN_ERROR));
    String questionText = turn.getQuestionText();
    if (!speech.getTextSha256().equals(QuestionSpeechHashes.of(questionText))) {
      speech.failSynthesis(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
      task.setStatus(AsyncTaskStatus.FAILED);
      task.setLastError(TEXT_MISMATCH_ERROR);
      metrics.afterCommit(() -> metrics.taskFailed(
          AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, "failed"));
      return new BeginResult(null, false, true);
    }
    UUID userId = users.findById(speech.getUserAccountId())
        .map(UserAccountEntity::getUserId)
        .orElseThrow(() -> new IllegalStateException("Question speech owner is missing"));
    return new BeginResult(new Work(
        task.getTaskId(), speech.getSpeechId(), userId, speech.getSessionId(),
        speech.getExecutionEpoch(), attemptGeneration, questionText,
        speech.getProviderId(), speech.getModelName(), speech.getVoiceName()), false, false);
  }

  /**
   * Steps 2, entirely outside the claim transaction (slow provider + media I/O). The voice
   * profile comes from the ROW's pinned provider/model/voice columns (captured at creation),
   * never from the live configuration: a redeploy with changed (or removed) TTS config must
   * not re-synthesize a retried speech with a different voice than the row and session
   * snapshot record.
   */
  private Outcome synthesizeMedia(Work work) {
    VoiceProfile profile = new VoiceProfile(work.provider(), work.model(), work.voice());
    long started = System.nanoTime();
    SynthesizedSpeech synthesized;
    try {
      synthesized = synthesizer.synthesize(work.questionText(), profile);
    } catch (SpeechSynthesisFailedException exception) {
      return fail(work, VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED,
          exception.getMessage(), elapsed(started));
    } catch (SpeechSynthesisRetryableException exception) {
      return retryable(work, elapsed(started));
    }
    if (synthesized.audio().length == 0) {
      return fail(work, VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED,
          EMPTY_AUDIO_ERROR, elapsed(started));
    }
    Duration latency = elapsed(started);
    StoredVoiceMedia stored;
    try {
      stored = mediaStore.store(
          new VoiceMediaKey(work.userId(), work.sessionId(),
              VoiceMediaKind.SPEECH, work.speechId()),
          new ByteArrayInputStream(synthesized.audio()),
          properties.maxUploadBytes());
    } catch (VoiceMediaUnsupportedException exception) {
      // A 200 whose body is not playable audio (e.g. a JSON error object) lands here: the
      // probe rejects it deterministically — retrying the same bytes cannot help.
      return fail(work, VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED,
          UNSUPPORTED_AUDIO_ERROR, latency);
    } catch (VoiceMediaTooLargeException exception) {
      return fail(work, VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED,
          "The TTS audio exceeds the configured media limit", latency);
    } catch (VoiceMediaProbeException | VoiceMediaStorageException exception) {
      return retryable(work, latency);
    }
    Outcome outcome = complete(work, stored, synthesized, latency);
    if (outcome != Outcome.TERMINAL) {
      // The final transaction did not persist the audio (stale fence or text drift): the
      // just-stored file is orphaned — best-effort delete after the transaction.
      deleteOrphanedAudio(work, stored.storageKey());
    }
    return outcome;
  }

  private Outcome complete(
      Work work, StoredVoiceMedia stored, SynthesizedSpeech synthesized, Duration latency) {
    Outcome outcome = transactions.execute(status -> {
      QuestionSpeechEntity speech = currentSpeech(work);
      if (speech == null) {
        return Outcome.STALE;
      }
      if (!textStillMatches(work, speech)) {
        speech.failSynthesis(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
        AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
        task.setStatus(AsyncTaskStatus.FAILED);
        task.setLastError(TEXT_MISMATCH_ERROR);
        metrics.afterCommit(() -> metrics.taskFailed(
            AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, "failed"));
        return Outcome.TERMINAL_WITHOUT_AUDIO;
      }
      speech.completeSynthesis(
          synthesized.providerRequestId(), stored.storageKey(), stored.mediaType(),
          stored.sizeBytes(), stored.duration() == null ? 0 : stored.duration().toMillis());
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setStatus(AsyncTaskStatus.COMPLETED);
      task.setLastError(null);
      metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.QUESTION_SPEECH_SYNTHESIS));
      return Outcome.TERMINAL;
    });
    if (outcome == Outcome.TERMINAL) {
      metrics.aiCall(work.provider(), "success", latency);
    }
    return outcome;
  }

  private Outcome fail(Work work, String code, String detail, Duration latency) {
    Outcome outcome = transactions.execute(status -> {
      QuestionSpeechEntity speech = currentSpeech(work);
      if (speech == null) {
        return Outcome.STALE;
      }
      speech.failSynthesis(code);
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setStatus(AsyncTaskStatus.FAILED);
      task.setLastError(detail);
      metrics.afterCommit(() -> metrics.taskFailed(
          AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, "failed"));
      return Outcome.TERMINAL;
    });
    if (outcome == Outcome.TERMINAL) {
      metrics.aiCall(work.provider(), "failure", latency);
    }
    return outcome;
  }

  /** Records the failure evidence on the current attempt, then re-throws for the retry pipeline. */
  private Outcome retryable(Work work, Duration latency) {
    Boolean current = transactions.execute(status -> {
      QuestionSpeechEntity speech = currentSpeech(work);
      if (speech == null) {
        return false;
      }
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setLastError(RETRYABLE_ERROR);
      return true;
    });
    metrics.aiCall(work.provider(), "failure", latency);
    if (!current) {
      return Outcome.STALE;
    }
    throw new SpeechSynthesisRetryableException(RETRYABLE_ERROR, work.attemptGeneration());
  }

  /**
   * Retry exhaustion (plan §11 step 8): the speech becomes FAILED with
   * {@code VOICE_QUESTION_SPEECH_FAILED} (so Task 8's retry endpoint can manually retry) and
   * the task becomes DEAD. Every fence is checked — task epoch/attempt and speech epoch — so
   * a dead-lettered message of an older generation can never terminalize a newer row; a
   * message that died before the claim transaction ever ran still moves its PENDING row to
   * FAILED (the pre-approved PENDING → FAILED transition, mirror of the recording's
   * UPLOADED → FAILED).
   */
  private boolean markDead(
      AsyncTaskEntity task,
      QuestionSpeechEntity speech,
      int expectedAttemptGeneration,
      int expectedExecutionEpoch) {
    if (task.getExecutionEpoch() != expectedExecutionEpoch
        || task.getAttemptCount() != expectedAttemptGeneration
        || speech.getExecutionEpoch() != expectedExecutionEpoch) {
      return false;
    }
    if (task.getStatus() == AsyncTaskStatus.DEAD
        && speech.getStatus() == QuestionSpeechStatus.FAILED) {
      return true;
    }
    if ((task.getStatus() != AsyncTaskStatus.PENDING
        && task.getStatus() != AsyncTaskStatus.PUBLISHED)
        || (speech.getStatus() != QuestionSpeechStatus.PENDING
        && speech.getStatus() != QuestionSpeechStatus.SYNTHESIZING)) {
      return false;
    }
    speech.failSynthesis(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED);
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setLastError("Voice synthesis retries exhausted");
    metrics.afterCommit(() -> metrics.taskFailed(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, "dead"));
    return true;
  }

  /** Final-transaction fence: the row must still be the claimed SYNTHESIZING execution. */
  private QuestionSpeechEntity currentSpeech(Work work) {
    QuestionSpeechEntity speech = speeches.findBySpeechId(work.speechId()).orElse(null);
    if (speech == null
        || speech.getExecutionEpoch() != work.epoch()
        || speech.getStatus() != QuestionSpeechStatus.SYNTHESIZING) {
      return null;
    }
    return speech;
  }

  /**
   * Best-effort cleanup of audio the final transaction did not persist (stale final fence or
   * text drift): the storage key is immutable and shared across generations by design
   * (rewrite semantics), so a delete must never remove a file a live row references — the
   * guard deletes only when the row is absent or FAILED without a storage key. The residual
   * window (a crash between the store and this cleanup, or a retry racing the re-read) can
   * still leave an unreferenced file; Task 11's sweeper owns that reclamation, and readers
   * tolerate missing files per the store's {@link VoiceMediaNotFoundException} contract.
   */
  private void deleteOrphanedAudio(Work work, String storageKey) {
    Boolean deletable = transactions.execute(status -> speeches.findBySpeechId(work.speechId())
        .map(speech -> speech.getStatus() == QuestionSpeechStatus.FAILED
            && speech.getStorageKey() == null)
        .orElse(true));
    if (!Boolean.TRUE.equals(deletable)) {
      return;
    }
    try {
      mediaStore.delete(storageKey);
    } catch (VoiceMediaNotFoundException | IllegalArgumentException ignored) {
      // nothing installed at the key (or the key is invalid) — already clean
    }
  }

  /** The turn text must still hash to the value pinned at creation (immutable question text). */
  private boolean textStillMatches(Work work, QuestionSpeechEntity speech) {
    InterviewTurnEntity turn = turns.findById(speech.getTurnId()).orElse(null);
    return turn != null
        && speech.getTextSha256().equals(QuestionSpeechHashes.of(turn.getQuestionText()));
  }

  private AsyncTaskEntity requireMatchingTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.QUESTION_SPEECH_SYNTHESIS
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Voice synthesis message identity is invalid");
    }
    return requireMatchingTaskById(message.taskId());
  }

  private AsyncTaskEntity requireMatchingTaskById(UUID taskId) {
    AsyncTaskEntity task = tasks.findByTaskId(Objects.requireNonNull(taskId, "taskId"))
        .orElseThrow(() -> new IllegalArgumentException("Voice synthesis task not found"));
    if (task.getTaskType() != AsyncTaskType.QUESTION_SPEECH_SYNTHESIS
        || task.getBizKey() == null
        || !task.getBizKey().startsWith(QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX)) {
      throw new IllegalArgumentException("Voice synthesis message does not match stored task");
    }
    return task;
  }

  private QuestionSpeechEntity requireSpeech(AsyncTaskEntity task) {
    UUID speechId;
    try {
      String bizKey = task.getBizKey();
      speechId = UUID.fromString(bizKey.substring(
          QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX.length()));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Voice synthesis business key is invalid");
    }
    QuestionSpeechEntity speech = speeches.findBySpeechId(speechId)
        .orElseThrow(() -> new IllegalArgumentException("Question speech not found"));
    if (!speech.getUserAccountId().equals(task.getUserAccountId())) {
      throw new IllegalArgumentException("Question speech owner does not match the task");
    }
    return speech;
  }

  private static Duration elapsed(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos);
  }

  public enum Outcome {
    /** The final transaction persisted the audio (or terminalized without any stored audio). */
    TERMINAL,
    /**
     * The final transaction terminalized the message without persisting the audio (text
     * drift after the store): the just-stored file is deleted after the transaction; the
     * listener completes the claim exactly like TERMINAL.
     */
    TERMINAL_WITHOUT_AUDIO,
    STALE
  }

  public record VoiceSynthesisTarget(
      UUID speechId, boolean terminal, int attemptGeneration, int executionEpoch) {}

  private record Work(
      UUID taskId, UUID speechId, UUID userId, Long sessionId,
      long epoch, int attemptGeneration, String questionText,
      String provider, String model, String voice) {}

  private record BeginResult(Work work, boolean stale, boolean terminal) {}
}
