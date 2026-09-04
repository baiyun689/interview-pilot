package interview.pilot.voice.realtime.asr;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import interview.pilot.voice.realtime.config.RealtimeVoiceProperties;

/**
 * DashScope Qwen3-ASR-Realtime streaming recognizer, ported from the reference implementation and
 * hardened for the multi-session server:
 * <ul>
 *   <li>one upstream {@link OmniRealtimeConversation} per interview session id in a concurrent map;</li>
 *   <li>server-side VAD auto-segments utterances, so the browser only streams raw PCM;</li>
 *   <li>the connect handshake runs on a virtual thread (never blocks a WebSocket container thread);</li>
 *   <li>{@code onClose} removes only its own connection via identity compare, so a restarted
 *       session's stale close cannot erase the new connection (the classic "silent after turn N");</li>
 *   <li>{@link #restart} stops, waits briefly and re-establishes, then verifies readiness.</li>
 * </ul>
 */
public class DashScopeStreamingAsrClient implements StreamingAsrClient, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(DashScopeStreamingAsrClient.class);

  private final String url;
  private final String model;
  private final String apiKey;
  private final String language;
  private final String format;
  private final int sampleRate;
  private final boolean turnDetection;
  private final String turnDetectionType;
  private final int silenceDurationMs;

  private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

  public DashScopeStreamingAsrClient(RealtimeVoiceProperties properties, String apiKey) {
    RealtimeVoiceProperties.Asr asr = properties.asr();
    this.url = asr.url();
    this.model = asr.model();
    this.apiKey = apiKey;
    this.language = asr.language();
    this.format = asr.format();
    this.sampleRate = asr.sampleRate();
    this.turnDetection = asr.enableTurnDetection();
    this.turnDetectionType = asr.turnDetectionType();
    this.silenceDurationMs = asr.turnDetectionSilenceMs();
  }

  private Object lockFor(String sessionId) {
    return sessionLocks.computeIfAbsent(sessionId, key -> new Object());
  }

  @Override
  public void start(String sessionId, Consumer<String> onFinal,
                    Consumer<String> onPartial, Consumer<Throwable> onError) {
    synchronized (lockFor(sessionId)) {
      startLocked(sessionId, onFinal, onPartial, onError);
    }
  }

  @Override
  public void restart(String sessionId, Consumer<String> onFinal,
                      Consumer<String> onPartial, Consumer<Throwable> onError) {
    synchronized (lockFor(sessionId)) {
      log.info("[realtime-asr {}] restarting upstream recognition", sessionId);
      stopLocked(sessionId);
      sleepQuietly(200);
      startLocked(sessionId, onFinal, onPartial, onError);
      for (int attempt = 0; attempt < 10; attempt++) {
        sleepQuietly(100);
        if (sessions.get(sessionId) != null) {
          log.info("[realtime-asr {}] upstream recognition re-established", sessionId);
          return;
        }
      }
      log.warn("[realtime-asr {}] restart not confirmed within 1s", sessionId);
    }
  }

  private void startLocked(String sessionId, Consumer<String> onFinal,
                           Consumer<String> onPartial, Consumer<Throwable> onError) {
    if (sessions.containsKey(sessionId)) {
      throw new IllegalStateException("ASR session already exists: " + sessionId);
    }
    try {
      OmniRealtimeParam param = OmniRealtimeParam.builder()
          .model(model).url(url).apikey(apiKey).build();
      AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

      OmniRealtimeCallback callback = new OmniRealtimeCallback() {
        @Override
        public void onOpen() {
          log.debug("[realtime-asr {}] upstream connected", sessionId);
        }

        @Override
        public void onEvent(JsonObject message) {
          handleEvent(sessionId, message, onFinal, onPartial, onError);
        }

        @Override
        public void onClose(int code, String reason) {
          OmniRealtimeConversation closed = conversationRef.get();
          log.warn("[realtime-asr {}] upstream closed code={} reason={}", sessionId, code, reason);
          sessions.compute(sessionId, (id, existing) ->
              existing != null && closed != null && existing.conversation == closed ? null : existing);
        }
      };

      OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
      conversationRef.set(conversation);
      // Register before connecting so isActive() is true immediately.
      sessions.put(sessionId, new AsrSession(conversation));

      Thread.ofVirtual().name("realtime-asr-connect-" + sessionId).start(() -> {
        try {
          conversation.connect();
          OmniRealtimeTranscriptionParam transcription = new OmniRealtimeTranscriptionParam();
          transcription.setLanguage(language);
          transcription.setInputSampleRate(sampleRate);
          transcription.setInputAudioFormat(format);
          OmniRealtimeConfig config = OmniRealtimeConfig.builder()
              .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
              .enableTurnDetection(turnDetection)
              .turnDetectionType(turnDetectionType)
              .turnDetectionThreshold(0.0f)
              .turnDetectionSilenceDurationMs(silenceDurationMs)
              .transcriptionConfig(transcription)
              .build();
          conversation.updateSession(config);
          log.info("[realtime-asr {}] recognition started (model={}, vad={}ms)",
              sessionId, model, silenceDurationMs);
        } catch (Exception ex) {
          log.error("[realtime-asr {}] connect failed", sessionId, ex);
          sessions.compute(sessionId, (id, existing) ->
              existing != null && existing.conversation == conversation ? null : existing);
          onError.accept(ex);
        }
      });
    } catch (Exception ex) {
      sessions.remove(sessionId);
      throw new IllegalStateException("Failed to create ASR session: " + sessionId, ex);
    }
  }

  @Override
  public void sendAudio(String sessionId, byte[] pcm) {
    AsrSession session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalStateException("No active ASR session: " + sessionId);
    }
    try {
      session.conversation.appendAudio(Base64.getEncoder().encodeToString(pcm));
    } catch (Exception ex) {
      // Surface so the WebSocket layer can restart the upstream and replay the chunk.
      throw new IllegalStateException("ASR append failed: " + sessionId, ex);
    }
  }

  @Override
  public boolean isActive(String sessionId) {
    return sessions.containsKey(sessionId);
  }

  @Override
  public void stop(String sessionId) {
    synchronized (lockFor(sessionId)) {
      stopLocked(sessionId);
    }
  }

  private void stopLocked(String sessionId) {
    AsrSession session = sessions.remove(sessionId);
    sessionLocks.remove(sessionId);
    if (session == null) {
      return;
    }
    try {
      session.conversation.endSession();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      log.debug("[realtime-asr {}] endSession ignored: {}", sessionId, ex.toString());
    }
    try {
      session.conversation.close();
    } catch (Exception ex) {
      log.debug("[realtime-asr {}] close ignored: {}", sessionId, ex.toString());
    }
  }

  private void handleEvent(String sessionId, JsonObject message,
                           Consumer<String> onFinal, Consumer<String> onPartial,
                           Consumer<Throwable> onError) {
    try {
      if (!message.has("type") || message.get("type").isJsonNull()) {
        return;
      }
      String eventType = message.get("type").getAsString();
      switch (eventType) {
        case "conversation.item.input_audio_transcription.completed" -> {
          if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            String transcript = message.get("transcript").getAsString();
            if (transcript != null && !transcript.isBlank()) {
              onFinal.accept(transcript);
            }
          }
        }
        case "conversation.item.input_audio_transcription.text",
             "conversation.item.input_audio_transcription.delta" -> {
          String partial = extractPartial(message);
          if (partial != null && !partial.isBlank() && onPartial != null) {
            onPartial.accept(partial);
          }
        }
        case "error" -> {
          log.error("[realtime-asr {}] upstream error event: {}", sessionId, message);
          onError.accept(new IllegalStateException("ASR upstream error: " + message));
        }
        default -> log.trace("[realtime-asr {}] unhandled event {}", sessionId, eventType);
      }
    } catch (Exception ex) {
      log.error("[realtime-asr {}] event handling failed", sessionId, ex);
      onError.accept(ex);
    }
  }

  /** Extract a displayable partial from {@code text+stash}, {@code delta} or nested {@code item}. */
  static String extractPartial(JsonObject message) {
    if (message.has("transcript") && message.get("transcript").isJsonPrimitive()) {
      return message.get("transcript").getAsString();
    }
    if (message.has("text") || message.has("stash")) {
      String prefix = primitiveText(message, "text");
      String suffix = primitiveText(message, "stash");
      String combined = (prefix == null ? "" : prefix) + (suffix == null ? "" : suffix);
      if (!combined.isBlank()) {
        return combined;
      }
    }
    if (message.has("delta")) {
      JsonElement delta = message.get("delta");
      if (delta.isJsonPrimitive()) {
        return delta.getAsString();
      }
      if (delta.isJsonObject()) {
        JsonObject obj = delta.getAsJsonObject();
        if (obj.has("text") && !obj.get("text").isJsonNull()) {
          return obj.get("text").getAsString();
        }
        if (obj.has("transcript") && !obj.get("transcript").isJsonNull()) {
          return obj.get("transcript").getAsString();
        }
      }
    }
    if (message.has("item") && message.get("item").isJsonObject()) {
      JsonObject item = message.getAsJsonObject("item");
      if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
        return item.get("transcript").getAsString();
      }
    }
    return null;
  }

  private static String primitiveText(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
      return obj.get(key).getAsString();
    }
    return null;
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void destroy() {
    sessions.keySet().forEach(this::stop);
    sessions.clear();
  }

  private record AsrSession(OmniRealtimeConversation conversation) {
  }
}
