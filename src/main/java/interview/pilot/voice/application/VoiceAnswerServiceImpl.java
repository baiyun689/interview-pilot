package interview.pilot.voice.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import interview.pilot.voice.domain.VoiceMimeTypes;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * Three-phase recording upload (plan §9):
 *
 * <ol>
 *   <li>short transaction: ownership + session + current turn validation and idempotency key
 *       check, insert of the RECEIVING row ({@code expires_at} = now + 10 minutes,
 *       {@code execution_epoch} = 0);</li>
 *   <li>outside any transaction: the multipart stream is copied to a temp file under the
 *       upload byte limit while its SHA-256 is computed (never buffered in memory), then
 *       probed for the real media type and duration;</li>
 *   <li>short transaction: the recording row (and the turn) are re-locked via the {@code
 *       @Version} optimistic lock, the temp file is atomically installed at the immutable
 *       storage key through {@link VoiceMediaStore} (its internal stage → probe → move is
 *       the only file work), metadata is saved, the unique VOICE_TRANSCRIPTION task row is
 *       created in the same transaction, and the recording becomes UPLOADED.</li>
 * </ol>
 *
 * <p>The task row is published by the existing reliable path ({@code PendingTaskDispatcher}
 * with publisher confirms and retry queues) — the same mechanism every other async task
 * uses; Task 4 only makes the new type routable. The receipt therefore returns the task id
 * immediately while the row sits PENDING until Task 5's listener picks it up.
 *
 * <p>Failure recovery: probe/validation failures move RECEIVING → FAILED with a safe_error
 * and delete the temp file; a phase-3 conflict deletes the just-written immutable file
 * immediately (a rolled-back transaction would otherwise leave a file no row references — an
 * orphan the sweeper would never reclaim); a crash after phase 1 leaves a RECEIVING row
 * whose {@code expires_at} + {@code idx_voice_recording_expiry (status, expires_at)} make it
 * discoverable for Task 11's sweeper, and replays of its requestId are 409
 * VOICE_UPLOAD_IN_PROGRESS until then.
 *
 * <p>Idempotent replay (quality-review carry-over): {@link VoiceMediaStore} exposes no
 * digest-read API, so a duplicate requestId re-stores the upload at the same immutable key
 * (atomic replace is benign for identical content) and compares the stored digest against
 * the row. On mismatch the file is deleted and the requestId is stably 409
 * REQUEST_ID_CONFLICT (the original recording's media is therefore not preserved for a
 * mismatched replay — the row keeps its status and the transcript, the transcription task
 * fails over, and the client must re-record with a fresh requestId). A replay of a FAILED
 * recording that never recorded a digest (size rejection interrupts streaming) cannot be
 * verified and is also 409. Replaying a probe-rejected upload re-runs the probe and fails
 * with the same deterministic 413/415 — the requestId outcome stays fixed, as the plan
 * requires.
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceAnswerServiceImpl implements VoiceAnswerModule {

  private static final Logger log = LoggerFactory.getLogger(VoiceAnswerServiceImpl.class);
  /** RECEIVING rows expire after 10 minutes; Task 11's sweeper reclaims them (plan §9). */
  private static final Duration RECEIVING_TTL = Duration.ofMinutes(10);
  private static final String TASK_BIZ_KEY_PREFIX = "voice-recording:";

  private final VoiceRecordingRepository recordings;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final AsyncTaskRepository tasks;
  private final VoiceMediaStore mediaStore;
  private final AudioProbe probe;
  private final VoiceProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public VoiceAnswerServiceImpl(
      VoiceRecordingRepository recordings,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      AsyncTaskRepository tasks,
      VoiceMediaStore mediaStore,
      AudioProbe probe,
      VoiceProperties properties,
      PlatformTransactionManager transactionManager) {
    this.recordings = recordings;
    this.sessions = sessions;
    this.turns = turns;
    this.tasks = tasks;
    this.mediaStore = mediaStore;
    this.probe = probe;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Clock.systemUTC();
  }

  @Override
  public VoiceRecordingReceipt accept(
      CurrentUser user, UUID sessionId, int turnNo, UUID uploadRequestId, MultipartFile audio) {
    Phase1 outcome = phase1(user, sessionId, turnNo, uploadRequestId);
    if (outcome.existing() != null) {
      return replay(user, outcome.existing(), audio);
    }
    long recordingId = outcome.recordingId();
    TempWrite staged = null;
    try {
      staged = writeTempBounded(audio);
      ProbedAudio probed = probe.probe(staged.path());
      rejectUnsupported(probed.mediaType());
      rejectDuration(probed.duration());
      return phase3(user, sessionId, outcome.sessionDatabaseId(),
          outcome.recordingUuid(), recordingId, staged.path(), probed);
    } catch (VoiceMediaTooLargeException exception) {
      failUpload(recordingId, VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE, null);
      throw new BusinessException(
          VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE,
          "Voice upload exceeds the configured limit", HttpStatus.CONTENT_TOO_LARGE);
    } catch (VoiceMediaUnsupportedException exception) {
      failUpload(recordingId, VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED,
          staged == null ? null : staged.sha256());
      throw new BusinessException(
          VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED,
          "Voice media format is not supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    } catch (VoiceMediaProbeException exception) {
      // Operational failure (ffprobe start/timeout/IO): recorded for diagnosis, kept off the wire.
      failUpload(recordingId, VoiceErrorCodes.VOICE_MEDIA_PROBE_FAILED,
          staged == null ? null : staged.sha256());
      throw exception;
    } catch (BusinessException exception) {
      if (VoiceErrorCodes.VOICE_DURATION_EXCEEDED.equals(exception.code())
          || VoiceErrorCodes.VOICE_TURN_NOT_CURRENT.equals(exception.code())) {
        failUpload(recordingId, exception.code(), staged == null ? null : staged.sha256());
      }
      throw exception;
    } finally {
      deleteQuietly(staged);
    }
  }

  @Override
  public VoiceRecordingView get(CurrentUser user, UUID sessionId, UUID recordingId) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var recording = requireRecording(session, recordingId);
    int turnNo = turns.findById(recording.getTurnId())
        .map(InterviewTurnEntity::getTurnNo)
        .orElseThrow(this::notFound);
    return view(recording, turnNo);
  }

  @Override
  public void retry(CurrentUser user, UUID sessionId, UUID recordingId) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var recording = requireRecording(session, recordingId);
    retryTranscription(recording.getId());
  }

  @Override
  public void discard(CurrentUser user, UUID sessionId, UUID recordingId) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var recording = requireRecording(session, recordingId);
    String storageKey = discardRecording(recording.getId());
    if (storageKey != null) {
      // Media deletion happens after the commit: a rolled-back transaction must not lose
      // the file of a recording that stays live. Missing files are already-gone (plan §14).
      try {
        mediaStore.delete(storageKey);
      } catch (VoiceMediaNotFoundException ignored) {
        // already gone — idempotent cleanup
      }
    }
  }

  /** Phase 1, retried once when the unique upload_request_id loses an insert race. */
  private Phase1 phase1(CurrentUser user, UUID sessionId, int turnNo, UUID uploadRequestId) {
    long ownerId = requireOwner(user);
    for (int attempt = 0; ; attempt++) {
      try {
        return transactions.execute(
            status -> phase1InTransaction(ownerId, sessionId, turnNo, uploadRequestId));
      } catch (DataIntegrityViolationException exception) {
        if (attempt == 1) {
          break;
        }
      }
    }
    // The competing insert committed: the idempotency key now decides the outcome.
    return transactions.execute(status -> replayOrConflict(ownerId, sessionId, uploadRequestId));
  }

  private Phase1 phase1InTransaction(
      long ownerId, UUID sessionId, int turnNo, UUID uploadRequestId) {
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var existing = recordings.findByUploadRequestId(uploadRequestId);
    if (existing.isPresent()) {
      return replayPhase(existing.get(), session);
    }
    if (session.getStatus() != SessionStatus.INTERVIEWING) {
      throw conflict(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT,
          "The interview is not accepting voice recordings");
    }
    if (turnNo != session.getCurrentTurnNo()) {
      throw conflict(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT,
          "The turn is not the current interview turn");
    }
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), turnNo)
        .orElseThrow(() -> conflict(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT,
            "The current interview turn is missing"));
    if (turn.getStatus() != TurnStatus.ASKED && turn.getStatus() != TurnStatus.FAILED) {
      throw conflict(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT,
          "The current interview turn is not accepting recordings");
    }
    var recording = recordings.save(VoiceRecordingEntity.receiving(
        ownerId, UUID.randomUUID(), uploadRequestId, session.getId(), turn.getId(),
        clock.instant().plus(RECEIVING_TTL)));
    recordings.flush();
    return Phase1.uploading(session.getId(), recording.getId(), recording.getRecordingId());
  }

  private Phase1 replayPhase(VoiceRecordingEntity existing, InterviewSessionEntity session) {
    if (!existing.getUserAccountId().equals(session.getUserAccountId())
        || !existing.getSessionId().equals(session.getId())) {
      throw notFound(); // cross-user/cross-session resources are hidden as 404
    }
    if (existing.getStatus() == VoiceRecordingStatus.RECEIVING) {
      throw conflict(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS,
          "A voice upload for this request is still being processed");
    }
    return Phase1.replay(existing);
  }

  private Phase1 replayOrConflict(long ownerId, UUID sessionId, UUID uploadRequestId) {
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var existing = recordings.findByUploadRequestId(uploadRequestId)
        .orElseThrow(() -> conflict("REQUEST_ID_CONFLICT",
            "uploadRequestId was already used concurrently"));
    return replayPhase(existing, session);
  }

  /**
   * Replays a duplicate requestId (carry-over decision, see class comment): re-store at the
   * immutable key, compare digests, delete on mismatch, and answer with the recording's
   * current status. The replay consumes the requestId forever: every later attempt yields
   * exactly the same outcome.
   */
  private VoiceRecordingReceipt replay(CurrentUser user, VoiceRecordingEntity existing,
      MultipartFile audio) {
    String expected = existing.getSha256();
    if (expected == null) {
      // The original upload was rejected before a full copy (size limit): no digest was
      // recorded, identity cannot be verified, so the requestId is treated as consumed.
      throw conflict("REQUEST_ID_CONFLICT", "uploadRequestId was already used for another upload");
    }
    VoiceMediaKey key = new VoiceMediaKey(
        user.userId(), existing.getSessionId(), VoiceMediaKind.RECORDING, existing.getRecordingId());
    TempWrite staged = null;
    try {
      staged = writeTempBounded(audio);
      StoredVoiceMedia stored = mediaStore.store(
          key, openTemp(staged.path()), properties.maxUploadBytes());
      if (!stored.sha256().equals(expected)) {
        mediaStore.delete(stored.storageKey());
        throw conflict("REQUEST_ID_CONFLICT", "uploadRequestId was already used for another upload");
      }
      if (existing.getStorageKey() == null) {
        // The row owns no media (upload-level FAILED): the re-store existed only for the
        // digest comparison and must not leave an unreferenced file behind.
        mediaStore.delete(stored.storageKey());
      }
      return receipt(user, existing.getRecordingId());
    } catch (VoiceMediaTooLargeException exception) {
      throw new BusinessException(
          VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE,
          "Voice upload exceeds the configured limit", HttpStatus.CONTENT_TOO_LARGE);
    } catch (VoiceMediaUnsupportedException exception) {
      // Re-storing identical rejected bytes fails the same way: the requestId outcome is fixed.
      throw new BusinessException(
          VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED,
          "Voice media format is not supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    } finally {
      deleteQuietly(staged);
    }
  }

  /**
   * Phase 3 with one optimistic-lock retry (the txn re-reads the row fresh each attempt).
   * Any conflict cleans the immutable key: a rolled-back transaction may already have
   * installed the file, and no row may reference it afterwards.
   */
  private VoiceRecordingReceipt phase3(
      CurrentUser user, UUID sessionId, Long sessionDatabaseId,
      UUID recordingUuid, long recordingId, Path temp, ProbedAudio probed) {
    String storageKey = new VoiceMediaKey(
        user.userId(), sessionDatabaseId, VoiceMediaKind.RECORDING, recordingUuid).storageKey();
    for (int attempt = 0; ; attempt++) {
      try {
        return transactions.execute(status ->
            phase3InTransaction(user, sessionId, recordingId, temp, probed));
      } catch (BusinessException exception) {
        // State conflicts (row already resolved / turn advanced) abort before the store, but
        // a rolled-back earlier attempt may still have installed the file — keep the key clean.
        deleteMediaQuietly(storageKey);
        throw exception;
      } catch (OptimisticLockingFailureException exception) {
        if (attempt == 1) {
          deleteMediaQuietly(storageKey);
          throw conflict("REQUEST_ID_CONFLICT", "uploadRequestId was already used concurrently");
        }
      }
    }
  }

  private VoiceRecordingReceipt phase3InTransaction(
      CurrentUser user, UUID sessionId, long recordingId, Path temp, ProbedAudio probed) {
    var recording = recordings.findById(recordingId).orElseThrow(() -> conflict(
        "REQUEST_ID_CONFLICT", "uploadRequestId was already used concurrently"));
    if (recording.getStatus() != VoiceRecordingStatus.RECEIVING) {
      // A concurrent discard/retry resolved the row while the media was being prepared.
      throw conflict("REQUEST_ID_CONFLICT", "uploadRequestId was already used concurrently");
    }
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, user.databaseId())
        .orElseThrow(this::notFound);
    if (session.getStatus() != SessionStatus.INTERVIEWING) {
      throw turnNotCurrent();
    }
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), session.getCurrentTurnNo())
        .orElseThrow(this::turnNotCurrent);
    if (turn.getStatus() != TurnStatus.ASKED && turn.getStatus() != TurnStatus.FAILED) {
      throw turnNotCurrent();
    }
    if (!turn.getId().equals(recording.getTurnId())) {
      throw turnNotCurrent();
    }
    VoiceMediaKey key = new VoiceMediaKey(
        user.userId(), session.getId(), VoiceMediaKind.RECORDING, recording.getRecordingId());
    StoredVoiceMedia stored = mediaStore.store(
        key, openTemp(temp), properties.maxUploadBytes());
    recording.acceptUpload(
        stored.storageKey(), stored.mediaType(), stored.sizeBytes(),
        stored.duration() == null ? 0 : stored.duration().toMillis(), stored.sha256());
    UUID taskId = createTranscriptionTask(recording);
    recordings.flush(); // forces the @Version optimistic-lock check inside the transaction
    return new VoiceRecordingReceipt(
        recording.getRecordingId(), recording.getStatus(), taskId);
  }

  /** Creates the unique VOICE_TRANSCRIPTION task row in the same transaction as UPLOADED. */
  private UUID createTranscriptionTask(VoiceRecordingEntity recording) {
    String bizKey = TASK_BIZ_KEY_PREFIX + recording.getRecordingId();
    var existing = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.VOICE_TRANSCRIPTION, bizKey, recording.getUserAccountId());
    if (existing.isPresent()) {
      return existing.get().getTaskId();
    }
    var task = tasks.save(AsyncTaskEntity.pending(
        recording.getUserAccountId(), AsyncTaskType.VOICE_TRANSCRIPTION, bizKey,
        "{\"recordingId\":\"" + recording.getRecordingId() + "\"}"));
    tasks.flush();
    return task.getTaskId();
  }

  private void retryTranscription(long recordingId) {
    for (int attempt = 0; ; attempt++) {
      try {
        transactions.executeWithoutResult(status -> {
          var recording = recordings.findById(recordingId).orElseThrow(this::notFound);
          switch (recording.getStatus()) {
            case FAILED -> {
              if (recording.getStorageKey() == null) {
                // An upload-level failure has no media and no transcription to retry; the
                // client must re-record with a fresh requestId.
                throw conflict(VoiceErrorCodes.VOICE_TRANSCRIPTION_FAILED,
                    "The recording failed before transcription; re-record instead");
              }
            }
            case RECEIVING -> throw conflict(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS,
                "The voice upload is still being processed");
            case UPLOADED, TRANSCRIBING -> throw conflict(
                VoiceErrorCodes.VOICE_TRANSCRIPTION_IN_PROGRESS,
                "The recording transcription is in progress");
            case READY, DISCARDED -> throw conflict(
                VoiceErrorCodes.VOICE_RECORDING_NOT_READY,
                "The recording has no failed transcription to retry");
            case ATTACHED -> throw conflict(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED,
                "The recording is already bound to an answer");
          }
          recording.beginTranscription(); // FAILED → TRANSCRIBING, fenced execution_epoch
          resetTranscriptionTask(recording);
          recordings.flush();
        });
        return;
      } catch (OptimisticLockingFailureException exception) {
        if (attempt == 1) {
          throw conflict("REQUEST_ID_CONFLICT", "Retry conflicted with another operation");
        }
      }
    }
  }

  /**
   * Resets the unique task row to a fresh PENDING execution (V9 fenced-epoch precedent): the
   * task epoch is bumped in lockstep with the recording epoch so stale listener messages
   * cannot overwrite the new result. The existing row is reused — uq_async_task_type_biz_key
   * guarantees one task row per recording.
   */
  private void resetTranscriptionTask(VoiceRecordingEntity recording) {
    String bizKey = TASK_BIZ_KEY_PREFIX + recording.getRecordingId();
    AsyncTaskEntity task = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.VOICE_TRANSCRIPTION, bizKey, recording.getUserAccountId())
        .orElseGet(() -> tasks.save(AsyncTaskEntity.pending(
            recording.getUserAccountId(), AsyncTaskType.VOICE_TRANSCRIPTION, bizKey,
            "{\"recordingId\":\"" + recording.getRecordingId() + "\"}")));
    task.setStatus(AsyncTaskStatus.PENDING);
    task.setExecutionEpoch(task.getExecutionEpoch() + 1);
    task.setLastPublishedAt(null);
    task.setLastError(null);
  }

  private String discardRecording(long recordingId) {
    for (int attempt = 0; ; attempt++) {
      try {
        return transactions.execute(status -> {
          var recording = recordings.findById(recordingId).orElseThrow(this::notFound);
          switch (recording.getStatus()) {
            case ATTACHED -> throw conflict(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED,
                "The recording is already bound to an answer");
            case UPLOADED, TRANSCRIBING -> throw conflict(
                VoiceErrorCodes.VOICE_TRANSCRIPTION_IN_PROGRESS,
                "The recording transcription is in progress");
            case RECEIVING, READY, FAILED -> recording.discard();
            case DISCARDED -> { /* idempotent: the media cleanup below still applies */ }
          }
          recordings.flush();
          return recording.getStorageKey();
        });
      } catch (OptimisticLockingFailureException exception) {
        if (attempt == 1) {
          throw conflict("REQUEST_ID_CONFLICT", "Discard conflicted with another operation");
        }
      }
    }
  }

  /** Best-effort RECEIVING → FAILED marking; never masks the original upload error. */
  private void failUpload(long recordingId, String error, String sha256) {
    try {
      transactions.executeWithoutResult(status ->
          recordings.findById(recordingId).ifPresent(recording -> {
            if (recording.getStatus() == VoiceRecordingStatus.RECEIVING) {
              recording.failUpload(error, sha256);
            }
            // A concurrent discard/retry already resolved the row; keep its outcome.
          }));
    } catch (RuntimeException exception) {
      log.warn("voice_upload_failure_marking recordingId={} code={}",
          recordingId, error, exception);
    }
  }

  private VoiceRecordingReceipt receipt(CurrentUser user, UUID recordingId) {
    var recording = recordings.findByRecordingId(recordingId).orElseThrow(this::notFound);
    UUID taskId = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.VOICE_TRANSCRIPTION, TASK_BIZ_KEY_PREFIX + recordingId,
        user.databaseId())
        .map(AsyncTaskEntity::getTaskId)
        .orElse(null);
    return new VoiceRecordingReceipt(recording.getRecordingId(), recording.getStatus(), taskId);
  }

  private VoiceRecordingEntity requireRecording(InterviewSessionEntity session, UUID recordingId) {
    var recording = recordings.findByRecordingId(recordingId).orElseThrow(this::notFound);
    if (!recording.getSessionId().equals(session.getId())) {
      throw notFound(); // cross-session resources are hidden as 404
    }
    return recording;
  }

  private VoiceRecordingView view(VoiceRecordingEntity recording, int turnNo) {
    boolean ready = recording.getStatus() == VoiceRecordingStatus.READY;
    return new VoiceRecordingView(
        recording.getRecordingId(), turnNo, recording.getStatus(),
        ready ? recording.getRawTranscript() : null,
        recording.getDurationMillis(),
        recording.getStatus() == VoiceRecordingStatus.FAILED,
        recording.getSafeError());
  }

  private void rejectUnsupported(String mediaType) {
    if (!VoiceMimeTypes.SUPPORTED.contains(mediaType)) {
      throw new VoiceMediaUnsupportedException();
    }
  }

  /**
   * Too-long audio is treated exactly like too-large content (decision, plan §2.4/§9): both
   * are deterministic "this recording is unusable" rejections, so both surface as 413 with
   * their own stable code. A missing duration (only possible with test doubles — the real
   * ffprobe probe never returns null) is not rejected.
   */
  private void rejectDuration(Duration duration) {
    if (duration != null && duration.compareTo(properties.maxRecordingDuration()) > 0) {
      throw new BusinessException(
          VoiceErrorCodes.VOICE_DURATION_EXCEEDED,
          "Voice recording exceeds the maximum duration", HttpStatus.CONTENT_TOO_LARGE);
    }
  }

  private static InputStream openTemp(Path temp) {
    try {
      return Files.newInputStream(temp);
    } catch (IOException exception) {
      throw new VoiceMediaStorageException("Unable to read the staged voice upload", exception);
    }
  }

  /**
   * Bounded stream-to-temp with an in-flight SHA-256; never buffers the upload in memory.
   * The temp file is self-cleaning: a size rejection interrupts the copy, but the file must
   * not survive (the caller only sees a {@link TempWrite} once the copy completed).
   */
  private TempWrite writeTempBounded(MultipartFile audio) {
    Path temp;
    try {
      temp = Files.createTempFile("voice-upload-", ".audio");
    } catch (IOException exception) {
      throw new VoiceMediaStorageException("Unable to stage the voice upload", exception);
    }
    try {
      MessageDigest digest = sha256Digest();
      long maxBytes = properties.maxUploadBytes();
      long copied = 0;
      byte[] buffer = new byte[8192];
      try (InputStream input = audio.getInputStream();
          OutputStream output = Files.newOutputStream(
              temp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        for (int read; (read = input.read(buffer)) >= 0;) {
          if (copied + read > maxBytes) {
            throw new VoiceMediaTooLargeException(maxBytes);
          }
          output.write(buffer, 0, read);
          digest.update(buffer, 0, read);
          copied += read;
        }
      }
      return new TempWrite(temp, HexFormat.of().formatHex(digest.digest()));
    } catch (VoiceMediaTooLargeException exception) {
      deleteQuietly(temp);
      throw exception;
    } catch (IOException exception) {
      deleteQuietly(temp);
      throw new VoiceMediaStorageException("Unable to stage the voice upload", exception);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private BusinessException notFound() {
    return new BusinessException(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND,
        "Voice recording not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private BusinessException turnNotCurrent() {
    return conflict(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT,
        "The interview turn no longer accepts recordings");
  }

  private static void deleteQuietly(TempWrite staged) {
    if (staged == null) {
      return;
    }
    deleteQuietly(staged.path());
  }

  private static void deleteQuietly(Path temp) {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException exception) {
      log.warn("voice_upload_temp_cleanup_failed path={}", temp, exception);
    }
  }

  private void deleteMediaQuietly(String storageKey) {
    try {
      mediaStore.delete(storageKey);
    } catch (VoiceMediaNotFoundException | IllegalArgumentException ignored) {
      // nothing was installed at the key (or the key is invalid) — already clean
    }
  }

  private record Phase1(
      Long sessionDatabaseId, Long recordingId, UUID recordingUuid, VoiceRecordingEntity existing) {
    static Phase1 uploading(long sessionDatabaseId, long recordingId, UUID recordingUuid) {
      return new Phase1(sessionDatabaseId, recordingId, recordingUuid, null);
    }

    static Phase1 replay(VoiceRecordingEntity existing) {
      return new Phase1(null, null, null, existing);
    }
  }

  private record TempWrite(Path path, String sha256) {}
}
