package interview.pilot.interview.voice;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Synchronous facade over Qwen realtime TTS; the WebSocket handler calls it off the I/O thread. */
@Service
public final class QwenTtsService {
  private final VoiceInterviewProperties properties;

  public QwenTtsService(VoiceInterviewProperties properties) {
    this.properties = properties;
  }

  public byte[] synthesize(String text) {
    if (!properties.enabled() || properties.qwen().apiKey().isBlank()
        || text == null || text.isBlank()) return new byte[0];
    var audio = new ByteArrayOutputStream();
    var done = new CountDownLatch(1);
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try {
      var client = new QwenTtsRealtime(
          QwenTtsRealtimeParam.builder().model(properties.qwen().ttsModel())
              .apikey(properties.qwen().apiKey()).build(), new QwenTtsRealtimeCallback() {
            @Override public void onOpen() { }
            @Override public void onEvent(JsonObject message) {
              String type = message.has("type") ? message.get("type").getAsString() : "";
              if ("response.audio.delta".equals(type) && message.has("delta")) {
                audio.writeBytes(Base64.getDecoder().decode(message.get("delta").getAsString()));
              } else if ("response.done".equals(type)) {
                done.countDown();
              } else if ("error".equals(type)) {
                error.set(new IllegalStateException(message.toString()));
                done.countDown();
              }
            }
            @Override public void onClose(int code, String reason) { done.countDown(); }
          });
      try {
        client.connect();
        client.updateSession(QwenTtsRealtimeConfig.builder()
            .voice(properties.qwen().voice())
            .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
            .mode("commit").languageType("Chinese").build());
        client.appendText(text);
        client.commit();
        done.await(20, TimeUnit.SECONDS);
        if (error.get() != null) return new byte[0];
        return audio.toByteArray();
      } finally {
        try { client.close(); } catch (Exception ignored) { }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new byte[0];
    } catch (Exception exception) {
      return new byte[0];
    }
  }
}
