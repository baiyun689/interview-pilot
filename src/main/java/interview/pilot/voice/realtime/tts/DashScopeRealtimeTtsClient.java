package interview.pilot.voice.realtime.tts;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;

import interview.pilot.voice.realtime.config.RealtimeVoiceProperties;

/**
 * DashScope Qwen-TTS-Realtime synthesizer. Each {@link #synthesize} opens a short-lived upstream
 * WebSocket, sends the text in {@code commit} mode, accumulates {@code response.audio.delta} PCM
 * chunks and returns once {@code response.done} arrives (bounded by a configurable latch timeout).
 *
 * <p>Instances hold no per-call state and are safe to share; the caller serializes turns per
 * session. Any upstream failure yields an empty array so the question text still reaches the
 * candidate (TTS is a degradable capability, never a blocker for the interview flow).
 */
public class DashScopeRealtimeTtsClient implements RealtimeTtsClient {

  private static final Logger log = LoggerFactory.getLogger(DashScopeRealtimeTtsClient.class);
  private static final int PCM_SAMPLE_RATE = 24_000;

  private final String apiKey;
  private final String model;
  private final String voice;
  private final String mode;
  private final String languageType;
  private final float speechRate;
  private final int volume;
  private final long timeoutMillis;

  public DashScopeRealtimeTtsClient(RealtimeVoiceProperties properties, String apiKey) {
    RealtimeVoiceProperties.Tts tts = properties.tts();
    this.apiKey = apiKey;
    this.model = tts.model();
    this.voice = tts.voice();
    this.mode = tts.mode();
    this.languageType = tts.languageType();
    this.speechRate = tts.speechRate();
    this.volume = tts.volume();
    this.timeoutMillis = tts.timeout().toMillis();
  }

  @Override
  public int sampleRate() {
    return PCM_SAMPLE_RATE;
  }

  @Override
  public boolean available() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public byte[] synthesize(String text) {
    if (text == null || text.isBlank()) {
      return new byte[0];
    }
    CountDownLatch latch = new CountDownLatch(1);
    ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    AtomicReference<Throwable> error = new AtomicReference<>();

    QwenTtsRealtime tts;
    try {
      QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
          .model(model).apikey(apiKey).build();
      tts = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
        @Override
        public void onOpen() {
          log.debug("[realtime-tts] upstream connected");
        }

        @Override
        public void onEvent(JsonObject message) {
          handleEvent(message, pcm, latch, error);
        }

        @Override
        public void onClose(int code, String reason) {
          latch.countDown();
        }
      });
      try {
        tts.connect();
        QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
            .voice(voice)
            .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
            .mode(mode)
            .languageType(languageType)
            .speechRate(speechRate)
            .volume(volume)
            .build();
        tts.updateSession(config);
        tts.appendText(text);
        tts.commit();
        boolean done = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!done) {
          log.warn("[realtime-tts] synthesis timeout after {}ms ({} chars)", timeoutMillis, text.length());
          return pcm.toByteArray();
        }
        if (error.get() != null) {
          log.warn("[realtime-tts] synthesis failed: {}", error.get().toString());
          return new byte[0];
        }
        byte[] result = pcm.toByteArray();
        log.debug("[realtime-tts] synthesized {} PCM bytes for {} chars", result.length, text.length());
        return result;
      } finally {
        try {
          tts.close();
        } catch (Exception ex) {
          log.debug("[realtime-tts] close ignored: {}", ex.toString());
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new byte[0];
    } catch (Exception ex) {
      log.warn("[realtime-tts] synthesis error: {}", ex.toString());
      return new byte[0];
    }
  }

  private void handleEvent(JsonObject message, ByteArrayOutputStream pcm,
                           CountDownLatch latch, AtomicReference<Throwable> error) {
    try {
      if (!message.has("type") || message.get("type").isJsonNull()) {
        return;
      }
      switch (message.get("type").getAsString()) {
        case "response.audio.delta" -> {
          if (message.has("delta") && message.get("delta").isJsonPrimitive()) {
            byte[] chunk = Base64.getDecoder().decode(message.get("delta").getAsString());
            synchronized (pcm) {
              pcm.write(chunk, 0, chunk.length);
            }
          }
        }
        case "response.done" -> latch.countDown();
        case "error" -> {
          log.error("[realtime-tts] upstream error: {}", message);
          error.set(new IllegalStateException("TTS upstream error: " + message));
          latch.countDown();
        }
        default -> { /* session.created / session.updated ignored */ }
      }
    } catch (Exception ex) {
      error.set(ex);
      latch.countDown();
    }
  }
}
