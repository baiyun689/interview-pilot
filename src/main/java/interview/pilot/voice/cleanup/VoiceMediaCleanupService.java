package interview.pilot.voice.cleanup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.async.policy.VoiceTranscriptionRetryPolicy;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.voice.application.VoiceSynthesisHandler;
import interview.pilot.voice.application.VoiceTranscriptionHandler;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;
import interview.pilot.voice.storage.VoiceStorageKeys;

/**
 * Scheduled voice media cleanup (plan §9/§11/§14, Task 11): one claim-gated run per tick that
 * sweeps, in order:
 *
 * <ol>
 *   <li>RECEIVING upload residue — rows past their 10-minute TTL (a crash between the upload's
 *       phase-1 and phase-3 transactions; plan §9). The row is deleted; its media file is
 *       deleted when the row carries a storage key. A phase-3 crash can leave a media file at
 *       the derived key of a still-null-key RECEIVING row — that file is unreferenced by
 *       definition and the orphan sweep (step 6) reclaims it after the grace period.</li>
 *   <li>DISCARDED recordings — the user's explicit deletion request (plan §8.3); file and row
 *       are removed without any time condition.</li>
 *   <li>Stuck TRANSCRIBING recordings whose VOICE_TRANSCRIPTION task has been PUBLISHED with no
 *       row activity past {@code stuckTaskThreshold} (default 30 min): the claim transaction
 *       committed but the message is gone — the pending dispatcher only rescans PENDING, so a
 *       crash there leaves the recording unretryable forever. The row is terminalized through
 *       the existing {@link VoiceTranscriptionHandler#markDeadCurrent} (recording FAILED with
 *       {@code VOICE_TRANSCRIPTION_FAILED}, task DEAD) rather than re-dispatched: markDead is
 *       the tested, epoch-fenced terminalization, it keeps the media file intact so the user's
 *       retry button re-publishes the task and re-transcribes from the same bytes, and
 *       re-dispatching would keep the row stuck if the message truly never comes back. The
 *       speech mirror (SYNTHESIZING + stale QUESTION_SPEECH_SYNTHESIS task) uses
 *       {@link VoiceSynthesisHandler#markDeadCurrent} identically.</li>
 *   <li>Retention (plan §14, default {@code VOICE_MEDIA_RETENTION}=7d from
 *       {@link VoiceProperties#retention()}): every recording — in ANY status, including
 *       ATTACHED — and every question_speech row of a session whose status is COMPLETED and
 *       whose {@code completed_at + retention < now}. Retention is the ONLY sweep that may
 *       touch a row that is not RECEIVING/DISCARDED; the session predicate is re-verified in
 *       the deletion transaction, so no recording of a live or unexpired session is ever
 *       deleted. question_speech has no expires_at column on purpose: the join-based sweep is
 *       index-efficient (the FK index InnoDB keeps for {@code fk_question_speech_session}
 *       covers the session_id scan; {@code idx_voice_recording_turn} covers the recording
 *       scan; the small interview_session scan needs no new index — hence no V24 migration).
 *       Terminal async_task rows of deleted recordings stay behind by design: they are
 *       finished history, nothing re-publishes them, and the recording's key is never reused.</li>
 *   <li>Orphaned media files — a directory walk for files whose row no longer exists (or whose
 *       row does not reference the exact key), deleted only after a 24-hour mtime grace so an
 *       in-flight store that will soon commit a referencing row can never lose its media.
 *       The DB is re-checked immediately before each delete. Stale store staging files
 *       ({@code .media-*.tmp}, crash residue of the atomic staged write) are reclaimed too.</li>
 * </ol>
 *
 * <h2>Mark-before-delete state machine (plan §14: "先标记、再删除文件、最后保存完成事实")</h2>
 *
 * <p>Each row moves through three steps; a crash at ANY point converges on the next run:
 *
 * <ol>
 *   <li><em>Mark</em> — a short transaction re-reads the row under {@code PESSIMISTIC_WRITE}
 *       and re-verifies the durable deletion predicate. The predicate itself is the mark: every
 *       sweepable state (RECEIVING+expired, DISCARDED, COMPLETED-session-expired) is terminal
 *       or time-anchored in the database, so no extra "cleaning" column is needed and no V24
 *       migration is required.</li>
 *   <li><em>File</em> — outside any transaction the immutable media file is deleted (best
 *       effort). {@link VoiceMediaNotFoundException} means already-gone and counts as success;
 *       {@link VoiceMediaStorageException} (a Windows sharing violation is wrapped here) or a
 *       security rejection defers the WHOLE row — file and row stay for a later run, and the
 *       batch continues.</li>
 *   <li><em>Completion fact</em> — a short transaction re-verifies the predicate under the row
 *       lock once more and deletes the row. A row upgraded between the mark and the file step
 *       (a pathological >10-minute stalled upload racing the residue sweep) fails this
 *       re-verification and is never deleted; its lost file then surfaces as a deterministic
 *       {@code VOICE_MEDIA_STORAGE_FAILED} transcription rather than a corruption.</li>
 * </ol>
 *
 * <p>Crash windows: after the mark → steps 2-3 re-run (file delete is idempotent); after the
 * file delete → step 2 is a no-op (absent = success) and step 3 finishes; during step 3 → the
 * transaction rolls back and the run repeats. A crash between a row deletion and its file
 * deletion never happens by construction — the file is always deleted first — so the only
 * residue a hard kill can produce is an unreferenced file, which is exactly what the orphan
 * sweep reclaims.
 *
 * <p>Concurrency: one Redis processing claim ({@value #RUN_CLAIM_KEY}, TTL
 * {@code claimTtl}) gates the whole run so two instances (or two overlapping ticks) never walk
 * the same candidates; every step is additionally idempotent and row-locked, so even a claim
 * loss degrades to harmless double-processing. Each phase claims at most {@code batchSize}
 * candidates (no loops over pages — the next tick continues where this one stopped).
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceMediaCleanupService {

  static final String RUN_CLAIM_KEY = "voice-cleanup:run";

  private static final Logger log = LoggerFactory.getLogger(VoiceMediaCleanupService.class);
  private static final String STAGING_TEMP_PREFIX = ".media-";
  private static final String STAGING_TEMP_SUFFIX = ".tmp";

  private final VoiceRecordingRepository recordings;
  private final QuestionSpeechRepository speeches;
  private final InterviewSessionRepository sessions;
  private final AsyncTaskRepository tasks;
  private final VoiceTranscriptionHandler transcriptionHandler;
  private final VoiceSynthesisHandler synthesisHandler;
  private final VoiceMediaStore mediaStore;
  private final ProcessingClaim claims;
  private final VoiceProperties voice;
  private final VoiceCleanupProperties cleanup;
  private final VoiceCleanupMetrics metrics;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public VoiceMediaCleanupService(
      VoiceRecordingRepository recordings,
      QuestionSpeechRepository speeches,
      InterviewSessionRepository sessions,
      AsyncTaskRepository tasks,
      VoiceTranscriptionHandler transcriptionHandler,
      VoiceSynthesisHandler synthesisHandler,
      VoiceMediaStore mediaStore,
      ProcessingClaim claims,
      VoiceProperties voice,
      VoiceCleanupProperties cleanup,
      VoiceCleanupMetrics metrics,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.recordings = recordings;
    this.speeches = speeches;
    this.sessions = sessions;
    this.tasks = tasks;
    this.transcriptionHandler = transcriptionHandler;
    this.synthesisHandler = synthesisHandler;
    this.mediaStore = mediaStore;
    this.claims = claims;
    this.voice = voice;
    this.cleanup = cleanup;
    this.metrics = metrics;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Scheduled entry point: claim-gated, batch-bounded, idempotent end to end. */
  public void runCleanup() {
    String token;
    try {
      token = claims.acquire(RUN_CLAIM_KEY, cleanup.claimTtl()).orElse(null);
    } catch (RuntimeException exception) {
      log.warn("voice cleanup skipped: run claim unavailable ({})", exception.getMessage());
      return;
    }
    if (token == null) {
      log.debug("voice cleanup skipped: another run holds the claim");
      return;
    }
    try {
      sweepReceivingResidue();
      sweepDiscarded();
      recoverStuckTranscriptions();
      recoverStuckSyntheses();
      sweepExpiredSessions();
      sweepOrphanFiles();
    } finally {
      try {
        claims.release(RUN_CLAIM_KEY, token);
      } catch (RuntimeException exception) {
        log.warn("voice cleanup claim release failed", exception);
      }
    }
  }

  // ------------------------------------------------------------ RECEIVING residue

  private void sweepReceivingResidue() {
    PageRequest batch = PageRequest.of(0, cleanup.batchSize());
    for (VoiceRecordingEntity candidate : recordings.findAllByStatusInAndExpiresAtBefore(
        List.of(VoiceRecordingStatus.RECEIVING), clock.instant(), batch)) {
      deleteRecording(candidate, row -> row.getStatus() == VoiceRecordingStatus.RECEIVING
          && row.getExpiresAt().isBefore(clock.instant()), "receiving");
    }
  }

  // ------------------------------------------------------------ DISCARDED recordings

  private void sweepDiscarded() {
    PageRequest batch = PageRequest.of(0, cleanup.batchSize());
    for (VoiceRecordingEntity candidate : recordings.findAllByStatusIn(
        List.of(VoiceRecordingStatus.DISCARDED), batch)) {
      deleteRecording(candidate,
          row -> row.getStatus() == VoiceRecordingStatus.DISCARDED, "discarded");
    }
  }

  /** One recording through the mark → file → completion-fact state machine. */
  private void deleteRecording(VoiceRecordingEntity candidate,
      Predicate<VoiceRecordingEntity> deletable, String kind) {
    Long id = candidate.getId();
    DeletionMark mark = transactions.execute(status -> {
      VoiceRecordingEntity row = recordings.findByIdForUpdate(id).orElse(null);
      if (row == null || !deletable.test(row)) {
        return null; // not (any longer) a candidate — a concurrent operation resolved it
      }
      return new DeletionMark(row.getStorageKey());
    });
    if (mark == null) {
      return;
    }
    if (!deleteFile(mark.storageKey(), kind)) {
      return; // deferred: the row keeps its file and is retried by a later run
    }
    transactions.executeWithoutResult(status ->
        recordings.findByIdForUpdate(id).filter(deletable).ifPresent(row -> {
          recordings.delete(row);
          metrics.deleted(kind);
        }));
  }

  /**
   * Deletes one immutable media file. {@code null} key (no media ever stored) is success;
   * absent file is success (idempotent cleanup); any storage failure — including the
   * {@link VoiceMediaStorageException} that wraps a Windows sharing violation — defers the row.
   */
  private boolean deleteFile(String storageKey, String kind) {
    if (storageKey == null) {
      return true;
    }
    try {
      mediaStore.delete(storageKey);
      return true;
    } catch (VoiceMediaNotFoundException ignored) {
      return true; // already gone — the completion fact still applies
    } catch (VoiceMediaStorageException exception) {
      log.warn("voice cleanup file deletion deferred key={} reason={}",
          storageKey, exception.getMessage());
      metrics.deferred(kind);
      return false;
    }
  }

  // ------------------------------------------------------------ stuck task recovery

  private void recoverStuckTranscriptions() {
    Instant stale = clock.instant().minus(cleanup.stuckTaskThreshold());
    PageRequest batch = PageRequest.of(0, cleanup.batchSize());
    for (AsyncTaskEntity task : tasks.findByTaskTypeAndStatusAndUpdatedAtBefore(
        AsyncTaskType.VOICE_TRANSCRIPTION, AsyncTaskStatus.PUBLISHED, stale, batch)) {
      UUID recordingId = bizKeyResourceId(task,
          VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX);
      if (recordingId == null) {
        continue;
      }
      VoiceRecordingEntity recording = recordings.findByRecordingId(recordingId).orElse(null);
      if (recording == null
          || recording.getStatus() != VoiceRecordingStatus.TRANSCRIBING) {
        continue; // the coarse task query needs the recording-side confirmation
      }
      try {
        boolean terminalized = transcriptionHandler.markDeadCurrent(
            new TaskMessage(task.getTaskId(), AsyncTaskType.VOICE_TRANSCRIPTION,
                task.getBizKey(), task.getExecutionEpoch()));
        if (terminalized) {
          metrics.deleted("stuck_transcription");
        }
      } catch (RuntimeException exception) {
        log.warn("voice cleanup stuck transcription deferred taskId={} reason={}",
            task.getTaskId(), exception.getMessage());
        metrics.deferred("stuck_transcription");
      }
    }
  }

  private void recoverStuckSyntheses() {
    Instant stale = clock.instant().minus(cleanup.stuckTaskThreshold());
    PageRequest batch = PageRequest.of(0, cleanup.batchSize());
    for (AsyncTaskEntity task : tasks.findByTaskTypeAndStatusAndUpdatedAtBefore(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, AsyncTaskStatus.PUBLISHED, stale, batch)) {
      UUID speechId = bizKeyResourceId(task, QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX);
      if (speechId == null) {
        continue;
      }
      QuestionSpeechEntity speech = speeches.findBySpeechId(speechId).orElse(null);
      if (speech == null || speech.getStatus() != QuestionSpeechStatus.SYNTHESIZING) {
        continue;
      }
      try {
        boolean terminalized = synthesisHandler.markDeadCurrent(
            new TaskMessage(task.getTaskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
                task.getBizKey(), task.getExecutionEpoch()));
        if (terminalized) {
          metrics.deleted("stuck_synthesis");
        }
      } catch (RuntimeException exception) {
        log.warn("voice cleanup stuck synthesis deferred taskId={} reason={}",
            task.getTaskId(), exception.getMessage());
        metrics.deferred("stuck_synthesis");
      }
    }
  }

  private UUID bizKeyResourceId(AsyncTaskEntity task, String prefix) {
    try {
      String bizKey = task.getBizKey();
      if (bizKey == null || !bizKey.startsWith(prefix)) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(bizKey.substring(prefix.length()));
    } catch (RuntimeException exception) {
      log.warn("voice cleanup skipped task with malformed bizKey taskId={}",
          task.getTaskId());
      return null;
    }
  }

  // ------------------------------------------------------------ retention

  private void sweepExpiredSessions() {
    Instant cutoff = clock.instant().minus(voice.retention());
    PageRequest batch = PageRequest.of(0, cleanup.batchSize());
    for (InterviewSessionEntity candidate : sessions.findAllByStatusAndCompletedAtBefore(
        SessionStatus.COMPLETED, cutoff, batch)) {
      sweepExpiredSession(candidate);
    }
  }

  /**
   * One expired COMPLETED session through the mark → files → completion-fact state machine:
   * every recording (any status, including ATTACHED) and every question_speech row is removed
   * together with its media file. A row whose file deletion failed is deferred — file and row
   * stay for a later run.
   */
  private void sweepExpiredSession(InterviewSessionEntity candidate) {
    Long sessionId = candidate.getId();
    ExpiredSessionRows rows = transactions.execute(status -> {
      InterviewSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
      if (session == null || !expired(session)) {
        return null;
      }
      return new ExpiredSessionRows(
          recordings.findAllBySessionId(session.getId()),
          speeches.findAllBySessionId(session.getId()));
    });
    if (rows == null) {
      return;
    }
    Set<Long> deletableRecordings = new HashSet<>();
    Set<Long> deletableSpeeches = new HashSet<>();
    for (VoiceRecordingEntity row : rows.recordings()) {
      if (deleteFile(row.getStorageKey(), "retention_recording")) {
        deletableRecordings.add(row.getId());
      }
    }
    for (QuestionSpeechEntity row : rows.speeches()) {
      if (deleteFile(row.getStorageKey(), "retention_speech")) {
        deletableSpeeches.add(row.getId());
      }
    }
    transactions.executeWithoutResult(status -> {
      InterviewSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
      if (session == null || !expired(session)) {
        return;
      }
      for (Long rowId : deletableRecordings) {
        recordings.findByIdForUpdate(rowId).ifPresent(row -> {
          if (row.getSessionId().equals(sessionId)) {
            recordings.delete(row);
            metrics.deleted("retention_recording");
          }
        });
      }
      for (Long rowId : deletableSpeeches) {
        speeches.findByIdForUpdate(rowId).ifPresent(row -> {
          if (row.getSessionId().equals(sessionId)) {
            speeches.delete(row);
            metrics.deleted("retention_speech");
          }
        });
      }
    });
  }

  private boolean expired(InterviewSessionEntity session) {
    return session.getStatus() == SessionStatus.COMPLETED
        && session.getCompletedAt() != null
        && session.getCompletedAt().plus(voice.retention()).isBefore(clock.instant());
  }

  private record ExpiredSessionRows(
      List<VoiceRecordingEntity> recordings, List<QuestionSpeechEntity> speeches) {}

  /** The locked, re-verified candidate; a null key means the row never stored media. */
  private record DeletionMark(String storageKey) {}

  // ------------------------------------------------------------ orphan files

  /**
   * Media-directory sweep for files no row references. The grace period (mtime older than
   * {@code orphanGrace}, default 24h) is the primary protection: files are immutable at their
   * key once stored, an orphan is only ever created by a crash window, and a fresh file may
   * belong to a store transaction whose row commits next — so only old files are candidates.
   * The DB is re-checked immediately before each delete (the row may reference the exact key).
   * Everything is best effort: unreadable directories, files deleted mid-walk, Windows sharing
   * violations and raced deletes defer or skip without failing the run.
   */
  private void sweepOrphanFiles() {
    Path root = voice.filesRoot().toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      return;
    }
    Instant cutoff = clock.instant().minus(cleanup.orphanGrace());
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          if (attributes.isRegularFile()) {
            deleteOrphanIfUnreferenced(file, root, cutoff);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
          // Unreadable directory or a file deleted mid-walk: never fail the whole sweep.
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException exception) {
      log.warn("voice cleanup orphan scan failed root={} reason={}", root, exception.getMessage());
    }
  }

  private void deleteOrphanIfUnreferenced(Path file, Path root, Instant cutoff) {
    String key = relativeKey(root, file);
    String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
    boolean stagingTemp = key == null
        && fileName.startsWith(STAGING_TEMP_PREFIX) && fileName.endsWith(STAGING_TEMP_SUFFIX);
    if (key == null && !stagingTemp) {
      return; // not a media key and not a store staging file
    }
    try {
      Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
      if (!modified.isBefore(cutoff)) {
        return; // grace not elapsed — an in-flight store may still claim this file
      }
      if (key != null && referencedByRow(key)) {
        return; // the row (re-checked right before the delete) references this exact key
      }
      Files.delete(file);
      if (key != null) {
        pruneEmptyParents(file.getParent(), root);
        metrics.deleted("orphan");
      } else {
        metrics.deleted("staging");
      }
    } catch (IOException exception) {
      // AccessDeniedException (Windows sharing violation) or a raced delete: defer, keep going.
      if (!(exception instanceof NoSuchFileException)) {
        metrics.deferred(stagingTemp ? "staging" : "orphan");
      }
    }
  }

  private static String relativeKey(Path root, Path file) {
    String relative = root.relativize(file).toString().replace('\\', '/');
    return VoiceStorageKeys.parse(relative) == null ? null : relative;
  }

  private boolean referencedByRow(String key) {
    VoiceStorageKeys.Parsed parsed = VoiceStorageKeys.parse(key);
    if (parsed.kind() == VoiceMediaKind.RECORDING) {
      return recordings.findByRecordingId(parsed.resourceId())
          .map(row -> key.equals(row.getStorageKey())).orElse(false);
    }
    return speeches.findBySpeechId(parsed.resourceId())
        .map(row -> key.equals(row.getStorageKey())).orElse(false);
  }

  /** Best-effort removal of now-empty key directories up to (not including) the media root. */
  private static void pruneEmptyParents(Path directory, Path root) {
    try {
      Path current = directory;
      while (current != null && current.startsWith(root) && !current.equals(root)) {
        try (var children = Files.list(current)) {
          if (children.findAny().isPresent()) {
            return;
          }
        }
        Files.delete(current);
        current = current.getParent();
      }
    } catch (IOException | RuntimeException ignored) {
      // best effort: a locked or recreated directory defers pruning to a later run
    }
  }
}
