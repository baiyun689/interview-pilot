package interview.pilot.voice.realtime.session;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.voice.realtime.config.RealtimeVoiceProperties;

/**
 * Per-connection voice session acting as a single-threaded actor.
 *
 * <p>All mutable conversational state (the merged-utterance buffer, the auto-submit debounce, the
 * processing flag, half-duplex gating) is touched only by this session's one daemon thread, which
 * removes the need for locks and CAS retries while strictly preserving turn order. Final ASR
 * segments arriving on DashScope callback threads are merely enqueued here.
 *
 * <p><b>Manual confirmation (default).</b> Server-side VAD still emits a final segment per sentence
 * for live subtitles and keeps merging them into the buffer, but nothing is sent until the candidate
 * explicitly issues a {@code submit} control (optionally carrying the last not-yet-final partial),
 * which drains the buffer immediately. This avoids an unstable silence cutoff committing a
 * half-formed answer. When {@code autoSubmit} is enabled, the last final segment instead arms a
 * {@code debounceMs} silence timer that drains automatically; a manual {@code submit} still wins.
 */
public class RealtimeVoiceSession {

  private static final Logger log = LoggerFactory.getLogger(RealtimeVoiceSession.class);
  private static final long BUSY_RETRY_MS = 400;

  /** Submits one merged utterance; the implementation runs the (blocking) turn engine + TTS. */
  @FunctionalInterface
  public interface UtteranceSubmitter {
    void submit(String mergedText);
  }

  private final WebSocketSession webSocket;
  private final CurrentUser user;
  private final UUID sessionId;
  private final ScheduledExecutorService executor;
  private final RealtimeVoiceProperties.Conversation config;
  private final UtteranceSubmitter submitter;

  private final StringBuilder mergeBuffer = new StringBuilder();
  private ScheduledFuture<?> debounce;
  private boolean processing;
  private volatile boolean aiSpeaking;
  private volatile long aiSpeakEndAt;
  private volatile boolean muted;
  private volatile long lastActivityAt = System.currentTimeMillis();
  private volatile boolean closed;

  public RealtimeVoiceSession(WebSocketSession webSocket, CurrentUser user, UUID sessionId,
                              RealtimeVoiceProperties.Conversation config,
                              UtteranceSubmitter submitter) {
    this.webSocket = webSocket;
    this.user = user;
    this.sessionId = sessionId;
    this.config = config;
    this.submitter = submitter;
    this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "realtime-voice-" + sessionId);
      thread.setDaemon(true);
      return thread;
    });
  }

  public CurrentUser user() {
    return user;
  }

  public UUID sessionId() {
    return sessionId;
  }

  public WebSocketSession webSocket() {
    return webSocket;
  }

  public void touch() {
    lastActivityAt = System.currentTimeMillis();
  }

  public long millisSinceActivity() {
    return System.currentTimeMillis() - lastActivityAt;
  }

  /** Append a final ASR sentence to the merge buffer; arm the silence timer only in auto-submit mode. */
  public void appendFinalSegment(String segment) {
    if (closed || segment == null || segment.isBlank()) {
      return;
    }
    touch();
    executor.execute(() -> {
      mergeBuffer.append(segment.trim());
      if (config.autoSubmitEnabled()) {
        armDebounce();
      }
    });
  }

  /** Manual submit; optional extra text is merged before draining. */
  public void requestSubmit(String extraText) {
    if (closed) {
      return;
    }
    touch();
    executor.execute(() -> {
      if (extraText != null && !extraText.isBlank()) {
        mergeBuffer.append(extraText.trim());
      }
      cancelDebounce();
      drainAndSubmit();
    });
  }

  /** Run the connect-time opening greeting under the same half-duplex gate as a normal turn. */
  public void runOpening(Runnable opening) {
    if (closed) {
      return;
    }
    executor.execute(() -> runTurnGated(opening));
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  public boolean isMuted() {
    return muted;
  }

  /**
   * Whether microphone uplink must be dropped: while the interviewer speaks, during the post-speech
   * cooldown (anti-echo / anti-interruption), while muted or after close.
   */
  public boolean uplinkSuppressed() {
    return closed || muted || aiSpeaking || System.currentTimeMillis() < aiSpeakEndAt;
  }

  /** Current merged-but-not-submitted text for an interim subtitle. */
  public String mergePreview() {
    return mergeBuffer.toString();
  }

  private void armDebounce() {
    cancelDebounce();
    debounce = executor.schedule(this::drainAndSubmit, config.debounceMs(), TimeUnit.MILLISECONDS);
  }

  private void cancelDebounce() {
    if (debounce != null) {
      debounce.cancel(false);
      debounce = null;
    }
  }

  private void drainAndSubmit() {
    if (closed) {
      return;
    }
    if (processing) {
      // A turn is still running (LLM + TTS). Re-check shortly instead of dropping the utterance.
      executor.schedule(this::drainAndSubmit, BUSY_RETRY_MS, TimeUnit.MILLISECONDS);
      return;
    }
    String text = mergeBuffer.toString().trim();
    mergeBuffer.setLength(0);
    if (text.isEmpty()) {
      return;
    }
    runTurnGated(() -> submitter.submit(text));
  }

  /** Runs one blocking turn with half-duplex gating; serialized by the single worker thread. */
  private void runTurnGated(Runnable turn) {
    processing = true;
    aiSpeaking = true;
    try {
      turn.run();
    } catch (RuntimeException ex) {
      log.warn("[realtime {}] turn failed: {}", sessionId, ex.toString());
      throw ex;
    } finally {
      aiSpeaking = false;
      aiSpeakEndAt = System.currentTimeMillis() + config.aiSpeakCooldownMs();
      processing = false;
    }
  }

  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    executor.shutdownNow();
  }
}
