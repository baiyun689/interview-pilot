package interview.pilot.interview.voice;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Owns one upstream Qwen ASR connection per browser voice session. */
@Service
public final class QwenAsrService {
  private final VoiceInterviewProperties properties;
  private final Map<String, OmniRealtimeConversation> sessions = new ConcurrentHashMap<>();

  public QwenAsrService(VoiceInterviewProperties properties) {
    this.properties = properties;
  }

  public boolean enabled() {
    return properties.enabled() && !properties.qwen().apiKey().isBlank();
  }

  public void start(
      String sessionId, Consumer<String> onFinal, Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    if (!enabled()) throw new IllegalStateException("VOICE_INTERVIEW_ENABLED and DASHSCOPE_API_KEY are required");
    if (sessions.containsKey(sessionId)) return;
    var qwen = properties.qwen();
    var reference = new java.util.concurrent.atomic.AtomicReference<OmniRealtimeConversation>();
    OmniRealtimeCallback callback = new OmniRealtimeCallback() {
      @Override public void onOpen() { }
      @Override public void onEvent(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        try {
          if ("conversation.item.input_audio_transcription.completed".equals(type)
              && message.has("transcript")) {
            onFinal.accept(message.get("transcript").getAsString());
          } else if (("conversation.item.input_audio_transcription.text".equals(type)
              || "conversation.item.input_audio_transcription.delta".equals(type))
              && message.has("text")) {
            onPartial.accept(message.get("text").getAsString());
          } else if ("error".equals(type)) {
            onError.accept(new IllegalStateException(message.toString()));
          }
        } catch (RuntimeException exception) {
          onError.accept(exception);
        }
      }
      @Override public void onClose(int code, String reason) {
        sessions.computeIfPresent(sessionId, (key, value) -> value == reference.get() ? null : value);
      }
    };
    try {
      var conversation = new OmniRealtimeConversation(
          OmniRealtimeParam.builder().model(qwen.asrModel()).url(qwen.asrUrl())
              .apikey(qwen.apiKey()).build(), callback);
      reference.set(conversation);
      sessions.put(sessionId, conversation);
      Thread.startVirtualThread(() -> {
        try {
          conversation.connect();
          var transcription = new OmniRealtimeTranscriptionParam();
          transcription.setLanguage("zh");
          transcription.setInputSampleRate(16_000);
          transcription.setInputAudioFormat("pcm");
          conversation.updateSession(OmniRealtimeConfig.builder()
              .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
              .enableTurnDetection(true).turnDetectionType("server_vad")
              .turnDetectionSilenceDurationMs(500)
              .transcriptionConfig(transcription).build());
        } catch (Exception exception) {
          sessions.remove(sessionId, conversation);
          onError.accept(exception);
        }
      });
    } catch (RuntimeException exception) {
      onError.accept(exception);
      throw exception;
    }
  }

  public void sendAudio(String sessionId, byte[] pcm) {
    OmniRealtimeConversation conversation = sessions.get(sessionId);
    if (conversation == null) throw new IllegalStateException("ASR session is not ready");
    conversation.appendAudio(Base64.getEncoder().encodeToString(pcm));
  }

  public void stop(String sessionId) {
    OmniRealtimeConversation conversation = sessions.remove(sessionId);
    if (conversation == null) return;
    try { conversation.endSession(); } catch (Exception ignored) { }
    try { conversation.close(); } catch (Exception ignored) { }
  }

  @PreDestroy
  public void close() {
    sessions.keySet().forEach(this::stop);
  }
}
