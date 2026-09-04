package interview.pilot.voice.realtime.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Realtime voice interview configuration ({@code app.voice.realtime.*}).
 *
 * <p>This is the WebSocket "talk-through" pipeline (streaming ASR → turn advance → realtime TTS).
 * It is layered on top of the existing {@code app.voice.*} file-recording pipeline and reuses its
 * API key, so it never carries credentials of its own. Every value has a default so that a
 * deployment only needs {@code app.voice.enabled=true} plus an ASR API key to turn it on.
 */
@ConfigurationProperties("app.voice.realtime")
public record RealtimeVoiceProperties(
    Boolean enabled,
    String path,
    Asr asr,
    Tts tts,
    Conversation conversation) {

  public RealtimeVoiceProperties {
    enabled = enabled == null ? Boolean.TRUE : enabled;
    path = (path == null || path.isBlank()) ? "/ws/voice-interview" : path;
    asr = asr == null
        ? new Asr(null, null, null, null, null, null, null, null, null) : asr;
    tts = tts == null ? new Tts(null, null, null, null, null, null, null, null) : tts;
    conversation = conversation == null
        ? new Conversation(null, null, null, null, null, null)
        : conversation;
  }

  /** Whether the realtime WebSocket pipeline is wired (still gated by {@code app.voice.enabled}). */
  public boolean isEnabled() {
    return enabled != null && enabled;
  }

  /** Streaming ASR (Qwen3-ASR-Realtime over an upstream WebSocket). */
  public record Asr(
      String url,
      String model,
      String language,
      String format,
      Integer sampleRate,
      Boolean enableTurnDetection,
      String turnDetectionType,
      Integer turnDetectionSilenceMs,
      Integer restartMaxAttempts) {

    public Asr {
      url = (url == null || url.isBlank())
          ? "wss://dashscope.aliyuncs.com/api-ws/v1/realtime" : url;
      model = (model == null || model.isBlank()) ? "qwen3-asr-flash-realtime" : model;
      language = (language == null || language.isBlank()) ? "zh" : language;
      format = (format == null || format.isBlank()) ? "pcm" : format;
      sampleRate = sampleRate == null ? 16_000 : sampleRate;
      enableTurnDetection = enableTurnDetection == null ? Boolean.TRUE : enableTurnDetection;
      turnDetectionType = (turnDetectionType == null || turnDetectionType.isBlank())
          ? "server_vad" : turnDetectionType;
      turnDetectionSilenceMs = turnDetectionSilenceMs == null ? 800 : turnDetectionSilenceMs;
      restartMaxAttempts = restartMaxAttempts == null ? 3 : restartMaxAttempts;
    }
  }

  /** Realtime TTS (Qwen-TTS-Realtime, synchronous whole-utterance synthesis, PCM 24kHz mono). */
  public record Tts(
      String model,
      String voice,
      Integer sampleRate,
      String mode,
      String languageType,
      Float speechRate,
      Integer volume,
      Duration timeout) {

    public Tts {
      model = (model == null || model.isBlank()) ? "qwen3-tts-flash-realtime" : model;
      voice = (voice == null || voice.isBlank()) ? "Cherry" : voice;
      sampleRate = sampleRate == null ? 24_000 : sampleRate;
      mode = (mode == null || mode.isBlank()) ? "commit" : mode;
      languageType = (languageType == null || languageType.isBlank()) ? "Chinese" : languageType;
      speechRate = speechRate == null ? 1.0f : speechRate;
      volume = volume == null ? 60 : volume;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
  }

  /**
   * Conversation pacing / half-duplex safeguards.
   *
   * <p>{@code autoSubmit} defaults to {@code false}: server-side VAD still splits sentences for live
   * subtitles, but an answer is only sent to the turn engine when the candidate explicitly sends a
   * {@code submit} control (manual confirmation avoids an unstable mid-thought silence cutoff). Set
   * it to {@code true} to restore silence-debounce auto-submit after {@code debounceMs}.
   */
  public record Conversation(
      Boolean autoSubmit,
      Integer debounceMs,
      Integer aiSpeakCooldownMs,
      Duration idleWarn,
      Duration idleTimeout,
      Integer maxMessageKb) {

    public Conversation {
      autoSubmit = autoSubmit == null ? Boolean.FALSE : autoSubmit;
      debounceMs = debounceMs == null ? 1_200 : debounceMs;
      aiSpeakCooldownMs = aiSpeakCooldownMs == null ? 800 : aiSpeakCooldownMs;
      idleWarn = idleWarn == null ? Duration.ofSeconds(270) : idleWarn;
      idleTimeout = idleTimeout == null ? Duration.ofMinutes(5) : idleTimeout;
      maxMessageKb = maxMessageKb == null ? 256 : maxMessageKb;
    }

    public boolean autoSubmitEnabled() {
      return autoSubmit != null && autoSubmit;
    }
  }
}
