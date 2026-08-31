package interview.pilot.interview.voice;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.application.AnswerProcessingResult;
import interview.pilot.interview.application.SubmitAnswerService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Browser-facing voice transport. Audio is forwarded to Qwen ASR, while the
 * actual interview turn is delegated to the same SubmitAnswerService as text.
 */
@Component
public final class VoiceInterviewWebSocketHandler extends TextWebSocketHandler {
  private final ObjectMapper objectMapper;
  private final QwenAsrService asr;
  private final QwenTtsService tts;
  private final SubmitAnswerService submitter;
  private final VoiceTicketService tickets;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();
  private final ExecutorService pipeline = Executors.newVirtualThreadPerTaskExecutor();

  public VoiceInterviewWebSocketHandler(
      ObjectMapper objectMapper, QwenAsrService asr, QwenTtsService tts,
      SubmitAnswerService submitter, VoiceTicketService tickets) {
    this.objectMapper = objectMapper;
    this.asr = asr;
    this.tts = tts;
    this.submitter = submitter;
    this.tickets = tickets;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
    String sessionId = sessionId(raw);
    CurrentUser user = tickets.consume(query(raw, "ticket"), UUID.fromString(sessionId));
    if (user == null) {
      raw.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    var connection = new Connection(
        new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024), user);
    if (connections.putIfAbsent(sessionId, connection) != null) {
      raw.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    send(connection, Map.of("type", "welcome", "sessionId", sessionId,
        "asrEnabled", asr.enabled(), "audioFormat", "pcm_s16le_16000",
        "ttsFormat", "pcm_s16le_24000"));
    if (asr.enabled()) {
      asr.start(sessionId, text -> onFinalTranscript(sessionId, text),
          text -> send(connection, Map.of("type", "transcript", "final", false, "text", text)),
          error -> sendError(connection, "ASR_FAILED", error.getMessage()));
    } else {
      send(connection, Map.of("type", "voice_disabled",
          "message", "未配置 Qwen ASR；可使用 text 消息测试同一面试核心"));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
    String id = sessionId(raw);
    Connection connection = connections.get(id);
    if (connection == null) return;
    try {
      JsonNode node = objectMapper.readTree(message.getPayload());
      String type = text(node, "type");
      if ("audio".equals(type)) {
        asr.sendAudio(id, Base64.getDecoder().decode(text(node, "data")));
      } else if ("text".equals(type)) {
        submit(id, connection, text(node, "text"));
      } else if ("control".equals(type) && "submit".equals(text(node, "action"))) {
        submit(id, connection, connection.takeTranscript());
      } else {
        sendError(connection, "INVALID_MESSAGE", "仅支持 audio、text 或 control/submit");
      }
    } catch (RuntimeException exception) {
      sendError(connection, "INVALID_MESSAGE", exception.getMessage());
    }
  }

  private void onFinalTranscript(String id, String text) {
    Connection connection = connections.get(id);
    if (connection == null || text == null || text.isBlank()) return;
    connection.appendTranscript(text);
    send(connection, Map.of("type", "transcript", "final", true, "text", text));
  }

  private void submit(String id, Connection connection, String answer) {
    if (answer == null || answer.isBlank()) {
      sendError(connection, "EMPTY_ANSWER", "请先说完或发送文字回答");
      return;
    }
    if (!connection.processing.compareAndSet(false, true)) {
      sendError(connection, "TURN_IN_PROGRESS", "上一轮仍在处理");
      return;
    }
    String normalized = answer.trim();
    connection.clearTranscript();
    send(connection, Map.of("type", "answer_accepted", "text", normalized));
    pipeline.execute(() -> {
      try {
        AnswerProcessingResult result = submitter.submit(
            connection.user, UUID.fromString(id), new SubmitAnswerRequest(UUID.randomUUID(), normalized));
        send(connection, Map.of("type", "turn_result", "result", result));
        if (result.nextQuestion() != null) {
          byte[] pcm = tts.synthesize(result.nextQuestion().question());
          send(connection, Map.of("type", "question", "question", result.nextQuestion(),
              "audioBase64", Base64.getEncoder().encodeToString(pcm)));
        } else {
          send(connection, Map.of("type", "completed", "status", result.sessionStatus().name()));
        }
      } catch (RuntimeException exception) {
        sendError(connection, "TURN_FAILED", exception.getMessage());
      } finally {
        connection.processing.set(false);
      }
    });
  }

  @Override
  public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
    String id = sessionId(raw);
    connections.remove(id);
    asr.stop(id);
  }

  private void send(Connection connection, Object payload) {
    try {
      if (connection.session.isOpen()) {
        connection.session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
      }
    } catch (Exception ignored) { }
  }

  private void sendError(Connection connection, String code, String message) {
    send(connection, Map.of("type", "error", "code", code,
        "message", message == null ? code : message));
  }

  private String sessionId(WebSocketSession session) {
    String[] parts = session.getUri().getPath().split("/");
    if (parts.length == 0) throw new IllegalArgumentException("sessionId is missing");
    return UUID.fromString(parts[parts.length - 1]).toString();
  }

  private String query(WebSocketSession session, String name) {
    String query = session.getUri().getQuery();
    if (query == null) return "";
    for (String part : query.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2 && name.equals(pair[0])) {
        return java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.asText();
  }

  private static final class Connection {
    private final WebSocketSession session;
    private final CurrentUser user;
    private final StringBuilder transcript = new StringBuilder();
    private final AtomicBoolean processing = new AtomicBoolean();

    private Connection(WebSocketSession session, CurrentUser user) {
      this.session = session;
      this.user = user;
    }

    private synchronized void appendTranscript(String text) {
      if (transcript.length() > 0) transcript.append(' ');
      transcript.append(text);
    }

    private synchronized String takeTranscript() {
      return transcript.toString();
    }

    private synchronized void clearTranscript() {
      transcript.setLength(0);
    }
  }
}
