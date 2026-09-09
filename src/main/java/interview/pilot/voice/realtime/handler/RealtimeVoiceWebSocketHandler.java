package interview.pilot.voice.realtime.handler;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.voice.realtime.asr.StreamingAsrClient;
import interview.pilot.voice.realtime.config.RealtimeVoiceProperties;
import interview.pilot.voice.realtime.dto.WsInbound;
import interview.pilot.voice.realtime.dto.WsOutbound;
import interview.pilot.voice.realtime.orchestrator.VoiceTurnOrchestrator;
import interview.pilot.voice.realtime.orchestrator.VoiceTurnOrchestrator.VoiceTurnOutcome;
import interview.pilot.voice.realtime.security.VoiceHandshakeInterceptor;
import interview.pilot.voice.realtime.session.RealtimeVoiceSession;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Single bidirectional WebSocket endpoint for a realtime voice interview.
 *
 * <p>Data plane: browser mic PCM → streaming ASR (partial/final subtitles) → per-session actor
 * merges final segments and submits only on the candidate's explicit {@code submit} confirmation
 * (manual mode) → {@link VoiceTurnOrchestrator} advances the existing turn engine → next question
 * TTS → WAV pushed back and played by the browser. Replaces the old record/upload/poll/confirm dance
 * with one persistent, half-duplex conversation.
 *
 * <p>Robustness carried over (and tightened) from the reference design:
 * {@link ConcurrentWebSocketSessionDecorator} for thread-safe writes, bounded ASR restart after an
 * upstream drop, post-speech cooldown to suppress the interviewer's own voice, idle warning/close,
 * and Micrometer counters. Writes never happen on a closed session.
 */
public class RealtimeVoiceWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(RealtimeVoiceWebSocketHandler.class);
  private static final int SEND_TIME_LIMIT_MS = 10_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

  private final RealtimeVoiceProperties properties;
  private final StreamingAsrClient asr;
  private final VoiceTurnOrchestrator orchestrator;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  private final Map<String, Connection> connections = new ConcurrentHashMap<>();

  public RealtimeVoiceWebSocketHandler(RealtimeVoiceProperties properties,
                                       StreamingAsrClient asr,
                                       VoiceTurnOrchestrator orchestrator,
                                       ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry) {
    this.properties = properties;
    this.asr = asr;
    this.orchestrator = orchestrator;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession rawSession) {
    Map<String, Object> attrs = rawSession.getAttributes();
    CurrentUser user = (CurrentUser) attrs.get(VoiceHandshakeInterceptor.ATTR_CURRENT_USER);
    java.util.UUID businessSessionId =
        (java.util.UUID) attrs.get(VoiceHandshakeInterceptor.ATTR_SESSION_ID);
    if (user == null || businessSessionId == null) {
      closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION);
      return;
    }

    WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
        rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
    String channelId = session.getId();
    RealtimeVoiceSession voiceSession = new RealtimeVoiceSession(
        session, user, businessSessionId, properties.conversation(),
        mergedText -> handleMergedUtterance(channelId, mergedText));
    connections.put(channelId, new Connection(session, voiceSession, user, businessSessionId));

    startAsr(channelId);
    send(session, WsOutbound.Control.of("welcome", "实时语音已连接"));
    // Re-speak the current question so the candidate hears it immediately (also covers refresh).
    voiceSession.runOpening(() -> playOpening(channelId));
    log.info("[realtime {}] voice channel opened for session {}", channelId, businessSessionId);
  }

  @Override
  protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) {
    Connection connection = connections.get(rawSession.getId());
    if (connection == null) {
      return;
    }
    RealtimeVoiceSession voice = connection.voiceSession;
    voice.touch();

    int maxBytes = properties.conversation().maxMessageKb() * 1024;
    if (message.getPayloadLength() > maxBytes) {
      send(rawSession, WsOutbound.Error.of("MESSAGE_TOO_LARGE", "语音消息超出大小限制"));
      return;
    }

    WsInbound inbound;
    try {
      inbound = objectMapper.readValue(message.getPayload(), WsInbound.class);
    } catch (Exception ex) {
      send(rawSession, WsOutbound.Error.of("BAD_MESSAGE", "无法解析的语音消息"));
      return;
    }

    if (inbound.isAudio()) {
      handleAudio(rawSession.getId(), voice, inbound.data());
    } else if (inbound.isControl()) {
      handleControl(rawSession.getId(), connection, inbound);
    }
  }

  private void handleAudio(String channelId, RealtimeVoiceSession voice, String base64Pcm) {
    if (base64Pcm == null || base64Pcm.isBlank() || voice.uplinkSuppressed()) {
      return; // half-duplex: drop mic frames while the interviewer speaks / cooldown / muted
    }
    try {
      byte[] pcm = Base64.getDecoder().decode(base64Pcm);
      asr.sendAudio(channelId, pcm);
    } catch (IllegalArgumentException ex) {
      send(voice.webSocket(), WsOutbound.Error.of("BAD_AUDIO", "音频编码错误"));
    } catch (RuntimeException ex) {
      log.warn("[realtime {}] uplink failed, attempting ASR restart: {}", channelId, ex.toString());
      restartAsr(channelId);
    }
  }

  private void handleControl(String channelId, Connection connection, WsInbound inbound) {
    RealtimeVoiceSession voice = connection.voiceSession;
    switch (inbound.normalizedAction()) {
      case "submit" -> {
        var binding=connection.binding;
        if(binding==null || (binding.recruitment() && inbound.turnNo()==null)
            || (inbound.turnNo()!=null && inbound.turnNo()!=binding.turnNo())) {
          send(connection.session,WsOutbound.Error.of("ANSWER_VERSION_CONFLICT","当前题目已变化，请重新连接后确认问题"));
          return;
        }
        voice.requestSubmit(inbound.text(),()->connection.binding==binding);
      }
      case "end_interview" -> closeQuietly(connection.session, CloseStatus.NORMAL);
      case "mute" -> {
        voice.setMuted(true);
        send(connection.session, WsOutbound.Control.of("muted", "已静音"));
      }
      case "unmute" -> {
        voice.setMuted(false);
        send(connection.session, WsOutbound.Control.of("unmuted", "已取消静音"));
      }
      default -> log.debug("[realtime {}] ignored control {}", channelId, inbound.action());
    }
  }

  // ---- turn processing (runs on the session's single worker thread) -----------------------

  private void startAsr(String channelId) {
    startAsr(channelId,false);
  }

  private void startAsr(String channelId,boolean restart) {
    Connection connection = connections.get(channelId);
    if (connection == null) {
      return;
    }
    int generation=connection.asrGeneration.incrementAndGet();
    java.util.function.Consumer<String> finalResult=finalText -> onAsrFinal(channelId,generation,finalText);
    java.util.function.Consumer<String> partialResult=partialText -> {
          Connection c = connections.get(channelId);
          if (c != null && c.asrGeneration.get()==generation && !c.voiceSession.uplinkSuppressed()) {
            send(c.session, WsOutbound.Subtitle.partial(partialText));
          }
        };
    java.util.function.Consumer<Throwable> failure=error -> {
          if(connection.asrGeneration.get()!=generation) return;
          log.warn("[realtime {}] ASR upstream error: {}", channelId, error.toString());
          restartAsr(channelId);
        };
    if(restart) asr.restart(channelId,finalResult,partialResult,failure);
    else asr.start(channelId,finalResult,partialResult,failure);
  }

  private void onAsrFinal(String channelId, int generation, String finalText) {
    Connection connection = connections.get(channelId);
    if (connection == null || connection.asrGeneration.get()!=generation || connection.voiceSession.uplinkSuppressed()) {
      return;
    }
    connection.voiceSession.appendFinalSegment(finalText,
        ()->connection.asrGeneration.get()==generation,
        ()->send(connection.session, WsOutbound.Subtitle.finalized(finalText)));
  }

  private void restartAsr(String channelId) {
    Connection connection = connections.get(channelId);
    if (connection == null) {
      return;
    }
    int attempt = connection.asrRestarts.incrementAndGet();
    if (attempt > properties.asr().restartMaxAttempts()) {
      send(connection.session, WsOutbound.Error.of("ASR_UNAVAILABLE",
          "语音识别连接多次失败，请检查网络后刷新重试"));
      return;
    }
    meterRegistry.counter("voice.realtime.asr.restart").increment();
    try {
      startAsr(channelId,true);
    } catch (RuntimeException ex) {
      log.error("[realtime {}] ASR restart failed", channelId, ex);
      send(connection.session, WsOutbound.Error.of("ASR_UNAVAILABLE", "语音识别重连失败"));
    }
  }

  private void playOpening(String channelId) {
    Connection connection = connections.get(channelId);
    if (connection == null) {
      return;
    }
    VoiceTurnOutcome outcome =
        orchestrator.speakCurrentTurn(connection.user, connection.businessSessionId);
    if (outcome == null) {
      return;
    }
    deliverQuestion(connection, outcome);
  }

  private void handleMergedUtterance(String channelId, String mergedText) {
    Connection connection = connections.get(channelId);
    if (connection == null) {
      return;
    }
    send(connection.session, WsOutbound.Control.of("thinking", "面试官正在思考…"));
    long start = System.currentTimeMillis();
    try {
      VoiceTurnOutcome outcome =
          orchestrator.submitAnswer(connection.user, connection.businessSessionId, mergedText, connection.binding);
      send(connection.session, WsOutbound.Control.of("answer_committed", mergedText));
      meterRegistry.timer("voice.realtime.turn").record(
          java.time.Duration.ofMillis(System.currentTimeMillis() - start));
      meterRegistry.counter("voice.realtime.turn.total").increment();
      if (outcome.ended()) {
        WsOutbound.Control ended = new WsOutbound.Control("control", "interview_ended",
            "面试已结束，正在生成评估", System.currentTimeMillis(), null,
            outcome.sessionStatus() == null ? null : outcome.sessionStatus().name());
        send(connection.session, ended);
        return;
      }
      if (!outcome.speechReady()) {
        meterRegistry.counter("voice.realtime.tts.degraded").increment();
      }
      deliverQuestion(connection, outcome);
    } catch (BusinessException ex) {
      log.info("[realtime {}] turn rejected: {} {}", channelId, ex.code(), ex.getMessage());
      send(connection.session, WsOutbound.Error.of(ex.code(), ex.getMessage()));
    } catch (RuntimeException ex) {
      log.error("[realtime {}] turn failed", channelId, ex);
      send(connection.session, WsOutbound.Error.of("INTERVIEW_PROCESSING_FAILED", "处理回答时出错，请重试"));
    }
  }

  private void deliverQuestion(Connection connection, VoiceTurnOutcome outcome) {
    int turnNo = outcome.nextTurnNo() == null ? 0 : outcome.nextTurnNo();
    boolean advancing=connection.binding!=null && connection.binding.turnNo()!=turnNo;
    connection.binding=orchestrator.bind(connection.user,connection.businessSessionId,turnNo);
    if(connection.binding.recruitment()) connection.voiceSession.requireManualConfirmation();
    // Freeze each ASR callback generation to the question that supplied its audio.
    // A provider may return an old final segment after the next question is delivered.
    if(advancing) {
      try {startAsr(connection.session.getId(),true);}
      catch(RuntimeException ex) {send(connection.session,WsOutbound.Error.of("ASR_UNAVAILABLE","下一题语音识别暂不可用，可切换文字继续"));}
    }
    if (outcome.nextQuestion() != null) {
      send(connection.session, WsOutbound.Text.of(outcome.nextQuestion(), turnNo));
    }
    if (outcome.speechReady() && outcome.nextQuestionWav().length > 0) {
      send(connection.session,
          WsOutbound.Audio.of(outcome.nextQuestionWavBase64(), outcome.nextQuestion(), turnNo));
    }
  }

  // ---- lifecycle / idle -------------------------------------------------------------------

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Connection connection = connections.remove(session.getId());
    if (connection == null) {
      return;
    }
    connection.voiceSession.close();
    asr.stop(session.getId());
    log.info("[realtime {}] channel closed ({})", session.getId(), status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.warn("[realtime {}] transport error: {}", session.getId(), exception.toString());
  }

  /** Warn once shortly before the idle limit, then close idle channels to release ASR/TTS slots. */
  @Scheduled(fixedDelay = 30_000L)
  public void closeIdleSessions() {
    long warnMs = properties.conversation().idleWarn().toMillis();
    long timeoutMs = properties.conversation().idleTimeout().toMillis();
    connections.forEach((channelId, connection) -> {
      long idle = connection.voiceSession.millisSinceActivity();
      if (idle >= timeoutMs) {
        send(connection.session, WsOutbound.Control.of("idle_timeout", "长时间未说话，已结束语音连接"));
        closeQuietly(connection.session, CloseStatus.SESSION_NOT_RELIABLE);
      } else if (idle >= warnMs && !connection.idleWarned.getAndSet(true)) {
        send(connection.session, WsOutbound.Control.of("idle_warning", "检测到您可能已离开，即将断开语音连接"));
      } else if (idle < warnMs) {
        connection.idleWarned.set(false);
      }
    });
  }

  private void send(WebSocketSession session, Object payload) {
    if (session == null || !session.isOpen()) {
      return;
    }
    try {
      session.sendMessage(new TextMessage(objectMapper.writeValueAsBytes(payload)));
    } catch (IOException ex) {
      log.debug("[realtime {}] send failed: {}", session.getId(), ex.toString());
    } catch (RuntimeException ex) {
      log.debug("[realtime {}] serialize/send failed: {}", session.getId(), ex.toString());
    }
  }

  private void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) {
        session.close(status);
      }
    } catch (IOException ex) {
      log.debug("close failed: {}", ex.toString());
    }
  }

  /** One live connection and its mutable counters. */
  private static final class Connection {
    private final WebSocketSession session;
    private final RealtimeVoiceSession voiceSession;
    private final CurrentUser user;
    private final java.util.UUID businessSessionId;
    private final AtomicInteger asrRestarts = new AtomicInteger();
    private final AtomicBoolean idleWarned = new AtomicBoolean();
    private final AtomicInteger asrGeneration = new AtomicInteger();
    private volatile VoiceTurnOrchestrator.TurnBinding binding;

    private Connection(WebSocketSession session, RealtimeVoiceSession voiceSession,
                       CurrentUser user, java.util.UUID businessSessionId) {
      this.session = session;
      this.voiceSession = voiceSession;
      this.user = user;
      this.businessSessionId = businessSessionId;
    }
  }
}
