package interview.pilot.voice.application;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.policy.VoiceTranscriptionRetryPolicy;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;

/**
 * Transcription half of the voice recording pair (plan §10), driven by the
 * {@code VOICE_TRANSCRIPTION} listener:
 *
 * <ol>
 *   <li>a short transaction validates the message identity, the task/recording epoch pair and
 *       the recording state (UPLOADED, or TRANSCRIBING left by a manual retry), moves the
 *       recording to TRANSCRIBING and the task to PUBLISHED, and assembles the recognition
 *       context from the session brief;</li>
 *   <li>outside any transaction the media is read and the {@link SpeechRecognizer} is called
 *       with the elapsed time measured;</li>
 *   <li>a final short transaction re-checks the epoch and the TRANSCRIBING state (stale
 *       messages lose — V9 fenced epoch), persists provider/model/requestId/transcript/asr
 *       duration and moves the recording to READY.</li>
 * </ol>
 *
 * <p>Failure taxonomy: operational failures (network/429/5xx/timeout, media IO) throw
 * {@link VoiceTranscriptionRetryableException} so the listener re-queues via the delayed-retry
 * pipeline; deterministic failures (4xx rejection, empty or oversized transcript, unparseable
 * response, missing media) and retry exhaustion move the recording to FAILED with a safe_error
 * and make the task terminal. Manual retry is the module endpoint's job — this handler never
 * resets tasks.
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceTranscriptionHandler {

  private static final Logger log = LoggerFactory.getLogger(VoiceTranscriptionHandler.class);

  public static final int MAX_TRANSCRIPT_CHARS = 20_000;

  static final String EMPTY_TRANSCRIPT_ERROR = "The ASR provider returned no transcript";
  static final String TRANSCRIPT_TOO_LONG_ERROR =
      "The transcript exceeds the " + MAX_TRANSCRIPT_CHARS + " character limit";
  static final String MISSING_MEDIA_ERROR = "The voice media is missing";
  static final String RETRYABLE_ERROR = "Voice transcription temporarily unavailable";

  private final VoiceRecordingRepository recordings;
  private final InterviewSessionRepository sessions;
  private final AsyncTaskRepository tasks;
  private final SpeechRecognizer recognizer;
  private final RecognitionContextAssembler contextAssembler;
  private final VoiceProperties properties;
  private final AiMetrics metrics;
  private final VoiceMetrics voiceMetrics;
  private final TransactionTemplate transactions;

  public VoiceTranscriptionHandler(
      VoiceRecordingRepository recordings,
      InterviewSessionRepository sessions,
      AsyncTaskRepository tasks,
      SpeechRecognizer recognizer,
      RecognitionContextAssembler contextAssembler,
      VoiceProperties properties,
      AiMetrics metrics,
      VoiceMetrics voiceMetrics,
      PlatformTransactionManager transactionManager) {
    this.recordings = recordings;
    this.sessions = sessions;
    this.tasks = tasks;
    this.recognizer = recognizer;
    this.contextAssembler = contextAssembler;
    this.properties = properties;
    this.metrics = metrics;
    this.voiceMetrics = voiceMetrics;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public VoiceTranscriptionTarget inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  private VoiceTranscriptionTarget inspectInTransaction(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    VoiceRecordingEntity recording = requireRecording(task);
    if (message.executionEpoch() < task.getExecutionEpoch()) {
      // A stale generation must never touch the row: the listener treats this as terminal.
      return new VoiceTranscriptionTarget(
          recording.getRecordingId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    if (message.executionEpoch() != task.getExecutionEpoch()) {
      throw new IllegalArgumentException("Voice transcription message epoch is invalid");
    }
    boolean taskTerminal = task.getStatus() == AsyncTaskStatus.COMPLETED
        || task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    // A terminal recording is any status the claim transaction cannot act on: READY/FAILED
    // are the direct outcomes, ATTACHED/DISCARDED are later user actions on a READY recording.
    // Without this, a duplicate delivery arriving after discard would dead-letter and requeue
    // forever (begin refuses, markDead refuses → IllegalStateException → requeue).
    boolean recordingTerminal = recording.getStatus() == VoiceRecordingStatus.READY
        || recording.getStatus() == VoiceRecordingStatus.FAILED
        || recording.getStatus() == VoiceRecordingStatus.ATTACHED
        || recording.getStatus() == VoiceRecordingStatus.DISCARDED;
    return new VoiceTranscriptionTarget(
        recording.getRecordingId(), taskTerminal && recordingTerminal,
        task.getAttemptCount(), task.getExecutionEpoch());
  }

  public Outcome transcribe(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> begin(message));
    return begin.stale() ? Outcome.STALE : transcribeMedia(begin.work());
  }

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      VoiceRecordingEntity recording = requireRecording(task);
      return markDead(task, recording, attemptGeneration, message.executionEpoch());
    }));
  }

  public boolean markDeadCurrent(TaskMessage message) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      VoiceRecordingEntity recording = requireRecording(task);
      return markDead(task, recording, task.getAttemptCount(), message.executionEpoch());
    }));
  }

  /**
   * Claim path (steps 1-2): validates again after the Redis claim was acquired, moves the
   * recording UPLOADED → TRANSCRIBING (or reuses the TRANSCRIBING state left by a manual
   * retry) without touching the epoch, and marks the task PUBLISHED.
   */
  private BeginResult begin(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    VoiceRecordingEntity recording = requireRecording(task);
    if (task.getExecutionEpoch() != message.executionEpoch()) {
      return new BeginResult(null, true); // a newer generation owns the row
    }
    switch (recording.getStatus()) {
      case UPLOADED -> recording.startTranscription();
      case TRANSCRIBING -> { /* manual retry already claimed it; the epoch fence protects */ }
      default -> throw new IllegalStateException("Voice recording is not transcription-ready");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Voice transcription task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    int attemptGeneration = task.getAttemptCount();
    task.setLastError(null);
    InterviewSessionEntity session = sessions.findById(recording.getSessionId())
        .orElseThrow(() -> new IllegalStateException("Voice recording session is missing"));
    RecognitionContext context = contextAssembler.assemble(session);
    return new BeginResult(new Work(
        task.getTaskId(), recording.getRecordingId(), recording.getSessionId(),
        recording.getStorageKey(), recording.getContentType(),
        recording.getExecutionEpoch(), attemptGeneration, context),
        false);
  }

  /** Step 3-5, entirely outside the claim transaction (slow provider + media I/O). */
  private Outcome transcribeMedia(Work work) {
    StoredVoiceMedia media = new StoredVoiceMedia(
        work.storageKey(), null, 0, work.contentType(), null);
    long started = System.nanoTime();
    Transcript transcript;
    try {
      transcript = recognizer.transcribe(media, work.context());
    } catch (VoiceTranscriptionFailedException exception) {
      return fail(work, VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED,
          exception.getMessage(), elapsed(started));
    } catch (VoiceMediaNotFoundException exception) {
      // The row claims the media exists; it does not — retrying cannot restore it.
      return fail(work, VoiceErrorCodes.VOICE_MEDIA_STORAGE_FAILED,
          MISSING_MEDIA_ERROR, elapsed(started));
    } catch (VoiceTranscriptionRetryableException exception) {
      return retryable(work, elapsed(started));
    } catch (VoiceMediaStorageException exception) {
      return retryable(work, elapsed(started));
    }
    Duration latency = elapsed(started);
    String text = transcript.text();
    if (text.isBlank()) {
      return fail(work, VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED,
          EMPTY_TRANSCRIPT_ERROR, latency);
    }
    if (text.length() > MAX_TRANSCRIPT_CHARS) {
      return fail(work, VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED,
          TRANSCRIPT_TOO_LONG_ERROR, latency);
    }
    return complete(work, transcript, latency);
  }

  private Outcome complete(Work work, Transcript transcript, Duration latency) {
    String provider = properties.asr().provider();
    Outcome outcome = transactions.execute(status -> {
      VoiceRecordingEntity recording = currentRecording(work);
      if (recording == null) {
        return Outcome.STALE;
      }
      recording.completeTranscription(
          provider, transcript.model(), transcript.providerRequestId(),
          transcript.text(), latency.toMillis());
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setStatus(AsyncTaskStatus.COMPLETED);
      task.setLastError(null);
      metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.VOICE_TRANSCRIPTION));
      return Outcome.TERMINAL;
    });
    if (outcome == Outcome.TERMINAL) {
      metrics.aiCall(provider, "success", latency);
      // The transcript text itself is never logged (privacy rule, plan §16).
      voiceMetrics.asr(provider, transcript.model(), "success", latency,
          transcript.text().length());
      log.info("voice_asr_success taskId={} recordingId={} sessionId={} executionEpoch={} "
              + "provider={} model={} providerRequestId={} asrDurationMillis={} "
              + "transcriptChars={}",
          work.taskId(), work.recordingId(), work.sessionId(), work.epoch(),
          provider, transcript.model(), transcript.providerRequestId(),
          latency.toMillis(), transcript.text().length());
    }
    return outcome;
  }

  private Outcome fail(Work work, String code, String detail, Duration latency) {
    String provider = properties.asr().provider();
    Outcome outcome = transactions.execute(status -> {
      VoiceRecordingEntity recording = currentRecording(work);
      if (recording == null) {
        return Outcome.STALE;
      }
      recording.failTranscription(code);
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setStatus(AsyncTaskStatus.FAILED);
      task.setLastError(detail);
      metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.VOICE_TRANSCRIPTION, "failed"));
      return Outcome.TERMINAL;
    });
    if (outcome == Outcome.TERMINAL) {
      metrics.aiCall(provider, "failure", latency);
      voiceMetrics.asr(provider, "unknown", "failure", latency, null);
      log.warn("voice_asr_failed taskId={} recordingId={} sessionId={} executionEpoch={} "
              + "code={} safeError={}",
          work.taskId(), work.recordingId(), work.sessionId(), work.epoch(), code, detail);
    }
    return outcome;
  }

  /** Records the failure evidence on the current attempt, then re-throws for the retry pipeline. */
  private Outcome retryable(Work work, Duration latency) {
    Boolean current = transactions.execute(status -> {
      VoiceRecordingEntity recording = currentRecording(work);
      if (recording == null) {
        return false;
      }
      AsyncTaskEntity task = requireMatchingTaskById(work.taskId());
      task.setLastError(RETRYABLE_ERROR);
      return true;
    });
    metrics.aiCall(properties.asr().provider(), "failure", latency);
    if (!current) {
      return Outcome.STALE;
    }
    log.warn("voice_asr_retryable taskId={} recordingId={} sessionId={} executionEpoch={}",
        work.taskId(), work.recordingId(), work.sessionId(), work.epoch());
    throw new VoiceTranscriptionRetryableException(RETRYABLE_ERROR, work.attemptGeneration());
  }

  /**
   * Retry exhaustion (plan §10 step 8): the recording becomes FAILED with
   * {@code VOICE_TRANSCRIPTION_FAILED} (so the module endpoint can manually retry) and the task
   * becomes DEAD. Every fence is checked — task epoch/attempt and recording epoch — so a
   * dead-lettered message of an older generation can never terminalize a newer row; a message
   * that died before the claim transaction ever ran still moves its UPLOADED row to FAILED
   * (the pre-approved UPLOADED → FAILED transition).
   */
  private boolean markDead(
      AsyncTaskEntity task,
      VoiceRecordingEntity recording,
      int expectedAttemptGeneration,
      int expectedExecutionEpoch) {
    if (task.getExecutionEpoch() != expectedExecutionEpoch
        || task.getAttemptCount() != expectedAttemptGeneration
        || recording.getExecutionEpoch() != expectedExecutionEpoch) {
      return false;
    }
    if (task.getStatus() == AsyncTaskStatus.DEAD
        && recording.getStatus() == VoiceRecordingStatus.FAILED) {
      return true;
    }
    if ((task.getStatus() != AsyncTaskStatus.PENDING
        && task.getStatus() != AsyncTaskStatus.PUBLISHED)
        || (recording.getStatus() != VoiceRecordingStatus.UPLOADED
        && recording.getStatus() != VoiceRecordingStatus.TRANSCRIBING)) {
      return false;
    }
    recording.failTranscription(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED);
    task.setStatus(AsyncTaskStatus.DEAD);
    task.setLastError("Voice transcription retries exhausted");
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.VOICE_TRANSCRIPTION, "dead"));
    voiceMetrics.retry("voice_transcription", "exhausted");
    log.warn("voice_asr_exhausted taskId={} recordingId={} sessionId={} executionEpoch={}",
        task.getTaskId(), recording.getRecordingId(), recording.getSessionId(),
        recording.getExecutionEpoch());
    return true;
  }

  /** Final-transaction fence: the row must still be the claimed TRANSCRIBING execution. */
  private VoiceRecordingEntity currentRecording(Work work) {
    VoiceRecordingEntity recording = recordings.findByRecordingId(work.recordingId())
        .orElse(null);
    if (recording == null
        || recording.getExecutionEpoch() != work.epoch()
        || recording.getStatus() != VoiceRecordingStatus.TRANSCRIBING) {
      return null;
    }
    return recording;
  }

  private AsyncTaskEntity requireMatchingTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.VOICE_TRANSCRIPTION
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Voice transcription message identity is invalid");
    }
    return requireMatchingTaskById(message.taskId());
  }

  private AsyncTaskEntity requireMatchingTaskById(UUID taskId) {
    AsyncTaskEntity task = tasks.findByTaskId(Objects.requireNonNull(taskId, "taskId"))
        .orElseThrow(() -> new IllegalArgumentException("Voice transcription task not found"));
    if (task.getTaskType() != AsyncTaskType.VOICE_TRANSCRIPTION
        || task.getBizKey() == null
        || !task.getBizKey().startsWith(VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX)) {
      throw new IllegalArgumentException("Voice transcription message does not match stored task");
    }
    return task;
  }

  private VoiceRecordingEntity requireRecording(AsyncTaskEntity task) {
    UUID recordingId;
    try {
      String bizKey = task.getBizKey();
      recordingId = UUID.fromString(bizKey.substring(
          VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX.length()));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Voice transcription business key is invalid");
    }
    VoiceRecordingEntity recording = recordings.findByRecordingId(recordingId)
        .orElseThrow(() -> new IllegalArgumentException("Voice recording not found"));
    if (!recording.getUserAccountId().equals(task.getUserAccountId())) {
      throw new IllegalArgumentException("Voice recording owner does not match the task");
    }
    return recording;
  }

  private static Duration elapsed(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos);
  }

  public enum Outcome {
    TERMINAL,
    STALE
  }

  public record VoiceTranscriptionTarget(
      UUID recordingId, boolean terminal, int attemptGeneration, int executionEpoch) {}

  private record Work(
      UUID taskId, UUID recordingId, Long sessionId, String storageKey, String contentType,
      long epoch, int attemptGeneration, RecognitionContext context) {}

  private record BeginResult(Work work, boolean stale) {}
}
