package interview.pilot.voice.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceRangeNotSatisfiableException;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/**
 * Question speech module implementation (plan §5.2/§8.5/§11):
 *
 * <ul>
 *   <li>{@code getOrSchedule} returns the existing row's view or, for a VOICE session with
 *       TTS configured, creates the missing row and its unique QUESTION_SPEECH_SYNTHESIS task
 *       in one transaction through the same {@link QuestionSpeechTaskCreator} the turn
 *       creators use (its uq_question_speech_turn race handling decides concurrent
 *       schedulers); TEXT sessions and unconfigured TTS answer the view-only NOT_AVAILABLE
 *       status — never a 404 for a legitimately existing turn.</li>
 *   <li>{@code retry} mirrors {@code VoiceAnswerServiceImpl.retryTranscription} exactly:
 *       the listener's terminal Redis claim is cleared first (an ACTIVE claim means a
 *       synthesis is genuinely in flight → 409 QUESTION_SPEECH_NOT_READY), then ONE
 *       transaction moves the speech FAILED → PENDING ({@link QuestionSpeechEntity#beginRetry}
 *       bumps its execution_epoch) and resets the task row to a fresh PENDING execution with
 *       its epoch bumped in lockstep, so stale listener messages from the old generation
 *       cannot overwrite the new result (V9 fenced-epoch precedent). The retried task is
 *       picked up by the existing {@code PendingTaskDispatcher} — this module never publishes
 *       directly.</li>
 *   <li>{@code open} validates session ownership and the speech's session (404 hides
 *       cross-user/cross-session resources) and the READY state (409 QUESTION_SPEECH_NOT_READY
 *       or QUESTION_SPEECH_FAILED), opens the store resource and — for a non-null range —
 *       resolves it against the media length, rejecting unsatisfiable ranges with
 *       {@link VoiceRangeNotSatisfiableException} (carrying the total length for the 416
 *       header) and returning a seeked, bounded stream built from the resource's validated
 *       {@code path} (Task 3) with {@code contentLength} still the total.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class QuestionSpeechServiceImpl implements QuestionSpeechModule {

  private final QuestionSpeechRepository speeches;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final AsyncTaskRepository tasks;
  private final ProcessingClaim claims;
  private final VoiceMediaStore mediaStore;
  private final QuestionSpeechTaskCreator questionSpeechTaskCreator;
  private final TransactionTemplate transactions;

  public QuestionSpeechServiceImpl(
      QuestionSpeechRepository speeches,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      AsyncTaskRepository tasks,
      ProcessingClaim claims,
      VoiceMediaStore mediaStore,
      QuestionSpeechTaskCreator questionSpeechTaskCreator,
      PlatformTransactionManager transactionManager) {
    this.speeches = speeches;
    this.sessions = sessions;
    this.turns = turns;
    this.tasks = tasks;
    this.claims = claims;
    this.mediaStore = mediaStore;
    this.questionSpeechTaskCreator = questionSpeechTaskCreator;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public QuestionSpeechView getOrSchedule(CurrentUser user, UUID sessionId, int turnNo) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), turnNo)
        .orElseThrow(this::notFound);
    var existing = speeches.findByTurnId(turn.getId());
    if (existing.isPresent()) {
      return view(existing.get(), session.getSessionId());
    }
    return schedule(session, turn);
  }

  @Override
  public QuestionSpeechMedia open(
      CurrentUser user, UUID sessionId, UUID speechId, HttpRange range) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var speech = requireSpeech(session, speechId);
    switch (speech.getStatus()) {
      case READY -> { /* the only openable state */ }
      case FAILED -> throw conflict(VoiceErrorCodes.QUESTION_SPEECH_FAILED,
          "The question speech synthesis failed");
      default -> throw conflict(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY,
          "The question speech is not ready");
    }
    VoiceMediaResource full = openMedia(speech);
    // The store does not persist probe metadata: the row's content_type (written at READY)
    // is the authoritative media type, falling back to the store's open-time value.
    String mediaType = speech.getContentType() != null
        ? speech.getContentType() : full.mediaType();
    if (range == null) {
      return new QuestionSpeechMedia(withMediaType(full, mediaType), etag(speech), null, null);
    }
    long length = full.contentLength();
    long start = range.getRangeStart(length);
    long end = range.getRangeEnd(length);
    if (start >= length) {
      closeQuietly(full);
      throw new VoiceRangeNotSatisfiableException(length);
    }
    // The resolved-and-clamped bounds travel with the media: the controller emits
    // Content-Range/Content-Length from them, never from its own re-computation.
    return new QuestionSpeechMedia(sliced(full, start, end, mediaType), etag(speech), start, end);
  }

  @Override
  public void retry(CurrentUser user, UUID sessionId, UUID speechId) {
    long ownerId = requireOwner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(this::notFound);
    var speech = requireSpeech(session, speechId);
    retrySynthesis(speech.getId());
  }

  /** Creates the missing speech row + task; a lost insert race retries the transaction once. */
  private QuestionSpeechView schedule(InterviewSessionEntity session, InterviewTurnEntity turn) {
    for (int attempt = 0; ; attempt++) {
      try {
        return transactions.execute(status -> scheduleInTransaction(session, turn));
      } catch (DataIntegrityViolationException | UnexpectedRollbackException exception) {
        // A concurrent scheduler won uq_question_speech_turn while this transaction raced:
        // the retried transaction re-reads the committed winner and answers its view.
        if (attempt == 1) {
          throw exception;
        }
      }
    }
  }

  private QuestionSpeechView scheduleInTransaction(
      InterviewSessionEntity session, InterviewTurnEntity turn) {
    UUID speechId = questionSpeechTaskCreator.createForTurn(session, turn);
    if (speechId == null) {
      return notAvailable(); // TEXT session or TTS unconfigured: nothing to schedule
    }
    return view(speeches.findBySpeechId(speechId).orElseThrow(), session.getSessionId());
  }

  /**
   * Manual retry, mirroring {@code VoiceAnswerServiceImpl.retryTranscription} exactly: clear
   * the listener's terminal claim first, then one transaction moves the speech and resets the
   * task with both epochs bumped in lockstep; an optimistic-lock conflict retries once.
   */
  private void retrySynthesis(long speechId) {
    for (int attempt = 0; ; attempt++) {
      try {
        clearSynthesisClaim(speechId);
        transactions.executeWithoutResult(status -> {
          var speech = speeches.findById(speechId).orElseThrow(this::notFound);
          if (speech.getStatus() != QuestionSpeechStatus.FAILED) {
            throw conflict(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY,
                "The question speech is not in a retryable failed state");
          }
          speech.beginRetry(); // FAILED → PENDING, fenced execution_epoch
          resetSynthesisTask(speech);
          speeches.flush();
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
   * Clears the listener's terminal processing claim (same pattern as the async retry
   * endpoint): a deterministic failure or success COMPLETES the claim, and an un-cleared
   * terminal claim would block the retried message's acquire forever (Task 7 wiring — the
   * claim key IS the voice bizKey per {@link QuestionSpeechSynthesisRetryPolicy}). The clear
   * runs before the transaction; an ACTIVE claim means a synthesis is genuinely in flight and
   * the retry is rejected.
   */
  private void clearSynthesisClaim(long speechId) {
    QuestionSpeechEntity speech = speeches.findById(speechId).orElseThrow(this::notFound);
    String claimKey = QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId();
    ProcessingClaim.ClearResult cleared;
    try {
      cleared = claims.clearTerminal(claimKey);
    } catch (RuntimeException exception) {
      throw conflict("TASK_RETRY_UNAVAILABLE", "Voice synthesis retry is temporarily unavailable");
    }
    if (cleared == ProcessingClaim.ClearResult.ACTIVE) {
      throw conflict(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY,
          "The question speech synthesis is in progress");
    }
  }

  /**
   * Resets the unique task row to a fresh PENDING execution (V9 fenced-epoch precedent): the
   * task epoch is joined to the speech's ALREADY-FENCED epoch (beginRetry bumped it first) so
   * stale listener messages cannot overwrite the new result. The existing row is reused —
   * uq_async_task_type_biz_key guarantees one task row per speech; a defensively recreated
   * row must join the exact speech generation too — a naive +1 over a fresh epoch-0 row would
   * silently break the lockstep on any retry after the first, and a current-generation
   * dead-letter could then never pass markDead's epoch triple-check (the speech would stick
   * in SYNTHESIZING forever).
   */
  private void resetSynthesisTask(QuestionSpeechEntity speech) {
    String bizKey = QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speech.getSpeechId();
    AsyncTaskEntity task = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, bizKey, speech.getUserAccountId())
        .orElseGet(() -> tasks.save(AsyncTaskEntity.pending(
            speech.getUserAccountId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, bizKey,
            "{\"speechId\":\"" + speech.getSpeechId() + "\"}")));
    task.setStatus(AsyncTaskStatus.PENDING);
    // The speech epoch is a long (V21 bigint) but the task epoch is an int (V9 schema); the
    // cast is safe because both advance once per manual retry in lockstep — 2^31 retries is
    // beyond any conceivable bound (the recording module shares this implicit limit).
    task.setExecutionEpoch((int) speech.getExecutionEpoch());
    task.setLastPublishedAt(null);
    task.setLastError(null);
  }

  private VoiceMediaResource openMedia(QuestionSpeechEntity speech) {
    if (speech.getStorageKey() == null) {
      throw notFound(); // a READY row always has a key (written atomically); corruption hides
    }
    try {
      return mediaStore.open(speech.getStorageKey());
    } catch (VoiceMediaNotFoundException exception) {
      // A READY row whose media file is absent (crash/orphan residue, plan §11): deterministic
      // not-found so the client falls back to text instead of a 500.
      throw notFound();
    }
  }

  /**
   * Bounds the range slice through the validated path (Task 3) with a seeked FileChannel
   * stream — never a full-file buffer. In-memory resources (test doubles) carry no path and
   * are sliced in memory; the store already bounded their size.
   */
  private VoiceMediaResource sliced(
      VoiceMediaResource full, long start, long end, String mediaType) {
    try {
      return slice(full, start, end, mediaType);
    } catch (IOException exception) {
      throw new VoiceMediaStorageException("Unable to seek voice media", exception);
    }
  }

  private VoiceMediaResource slice(
      VoiceMediaResource full, long start, long end, String mediaType) throws IOException {
    Path path = full.path();
    if (path == null) {
      byte[] all;
      try (InputStream input = full.inputStream()) {
        all = input.readAllBytes();
      }
      return new VoiceMediaResource(
          new ByteArrayInputStream(Arrays.copyOfRange(all, (int) start, (int) end + 1)),
          full.contentLength(), mediaType, null);
    }
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    boolean kept = false;
    try {
      channel.position(start);
      full.close(); // the store's stream is superseded by the seeked channel stream
      kept = true;
      return new VoiceMediaResource(
          new BoundedChannelInputStream(channel, end - start + 1),
          full.contentLength(), mediaType, path);
    } finally {
      if (!kept) {
        try {
          channel.close();
        } catch (IOException ignored) {
          // nothing was handed out; the seek/close failure is rethrown below
        }
      }
    }
  }

  private QuestionSpeechView view(QuestionSpeechEntity speech, UUID sessionUuid) {
    QuestionSpeechStatus status = speech.getStatus();
    boolean ready = status == QuestionSpeechStatus.READY;
    boolean failed = status == QuestionSpeechStatus.FAILED;
    return new QuestionSpeechView(
        speech.getSpeechId(),
        QuestionSpeechViewStatus.valueOf(status.name()),
        ready ? mediaUrl(sessionUuid, speech.getSpeechId()) : null,
        failed,
        failed ? speech.getSafeError() : null);
  }

  private static String mediaUrl(UUID sessionUuid, UUID speechId) {
    // The public session UUID is the session's wire identity — never its database id.
    return "/api/interviews/" + sessionUuid + "/speech/" + speechId + "/media";
  }

  /**
   * The store's open does not carry probe metadata (its {@code mediaType} is null): return a
   * resource that carries the row's content_type when it differs.
   */
  private static VoiceMediaResource withMediaType(
      VoiceMediaResource resource, String mediaType) {
    if (mediaType == null || mediaType.equals(resource.mediaType())) {
      return resource;
    }
    return new VoiceMediaResource(
        resource.inputStream(), resource.contentLength(), mediaType, resource.path());
  }

  /**
   * Weak etag over the speech id and the row's optimistic-lock version: the content at the
   * immutable storage key can only change across a re-synthesis, which always bumps the
   * version (FAILED → PENDING → SYNTHESIZING → READY all flush) — the etag changes exactly
   * then, while a READY row's audio stays byte-immutable.
   */
  private static String etag(QuestionSpeechEntity speech) {
    return "W/\"qs-" + speech.getSpeechId() + "-v" + speech.getVersion() + "\"";
  }

  private static QuestionSpeechView notAvailable() {
    return new QuestionSpeechView(
        null, QuestionSpeechViewStatus.NOT_AVAILABLE, null, false, null);
  }

  private QuestionSpeechEntity requireSpeech(InterviewSessionEntity session, UUID speechId) {
    var speech = speeches.findBySpeechId(speechId).orElseThrow(this::notFound);
    if (!speech.getSessionId().equals(session.getId())) {
      throw notFound(); // cross-session resources are hidden as 404
    }
    return speech;
  }

  private static long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private BusinessException notFound() {
    return new BusinessException(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND,
        "Question speech not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private static void closeQuietly(VoiceMediaResource resource) {
    try {
      resource.close();
    } catch (IOException ignored) {
      // best-effort: the stream is about to be replaced by the 416 response
    }
  }

  /** Reads at most {@code limit} bytes from a seeked channel, then reports EOF. */
  private static final class BoundedChannelInputStream extends InputStream {
    private final FileChannel channel;
    private long remaining;

    BoundedChannelInputStream(FileChannel channel, long limit) {
      this.channel = channel;
      this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int read = read(one, 0, 1);
      return read < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int wanted = (int) Math.min(length, remaining);
      int read = channel.read(ByteBuffer.wrap(buffer, offset, wanted));
      if (read < 0) {
        return -1;
      }
      remaining -= read;
      return read;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
