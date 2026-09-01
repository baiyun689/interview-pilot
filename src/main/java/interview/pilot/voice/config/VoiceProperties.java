package interview.pilot.voice.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import interview.pilot.voice.domain.VoiceSnapshot;

/**
 * Voice interview configuration (app.voice.*).
 *
 * <p>When {@code enabled} is false no validation runs at all: text interviews must
 * remain unconditionally available even with garbage or empty credentials.
 */
@ConfigurationProperties("app.voice")
public record VoiceProperties(
    boolean enabled,
    Path filesRoot,
    long maxUploadBytes,
    Duration maxRecordingDuration,
    Duration retention,
    Asr asr,
    Tts tts) {

  public VoiceProperties {
    validate(enabled, filesRoot, maxUploadBytes, maxRecordingDuration, retention, asr, tts);
  }

  private static void validate(
      boolean enabled, Path filesRoot, long maxUploadBytes, Duration maxRecordingDuration,
      Duration retention, Asr asr, Tts tts) {
    if (!enabled) {
      return;
    }
    if (filesRoot == null) {
      throw new IllegalArgumentException("Voice files root is required");
    }
    if (maxUploadBytes <= 0) {
      throw new IllegalArgumentException("Voice max upload bytes must be positive");
    }
    if (maxRecordingDuration == null || maxRecordingDuration.isZero()
        || maxRecordingDuration.isNegative()) {
      throw new IllegalArgumentException("Voice max recording duration must be positive");
    }
    if (maxRecordingDuration.toSeconds() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Voice max recording duration must fit in seconds");
    }
    if (retention == null || retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("Voice media retention must be positive");
    }
    if (asr == null) {
      throw new IllegalArgumentException("Voice ASR configuration is required");
    }
    if (!asrComplete(asr)) {
      throw new IllegalArgumentException(
          "Voice ASR requires a provider, model, base URL, and API key");
    }
    if (asr.timeout() == null || asr.timeout().isZero() || asr.timeout().isNegative()) {
      throw new IllegalArgumentException("Voice ASR timeout must be positive");
    }
    if (tts != null && (tts.timeout() == null || tts.timeout().isZero() || tts.timeout().isNegative())) {
      throw new IllegalArgumentException("Voice TTS timeout must be positive");
    }
  }

  /** ASR is required for voice input; TTS stays a degradable playback capability. */
  public boolean asrConfigured() {
    return enabled && asrComplete(asr);
  }

  /**
   * Canonical ASR readiness predicate shared by startup validation and runtime checks. The
   * DashScope Authorization header is {@code Bearer <api-key>} in every deployment mode — the
   * MaaS workspace form (workspace-specific hostname) still authenticates with an API key and
   * is not supported in this release — so an API key is the only accepted credential.
   */
  private static boolean asrComplete(Asr asr) {
    return asr != null
        && hasText(asr.provider())
        && hasText(asr.model())
        && hasText(asr.baseUrl())
        && hasText(asr.apiKey());
  }

  public boolean ttsConfigured() {
    return enabled && tts != null
        && hasText(tts.provider())
        && hasText(tts.model())
        && hasText(tts.voice());
  }

  public int maxRecordingSeconds() {
    long seconds = maxRecordingDuration == null ? 0 : maxRecordingDuration.toSeconds();
    return (int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
  }

  /** Immutable creation-time snapshot of the voice configuration. */
  public VoiceSnapshot toSnapshot() {
    if (!asrConfigured()) {
      throw new IllegalStateException("Voice ASR is not configured");
    }
    return new VoiceSnapshot(
        VoiceSnapshot.SCHEMA_VERSION,
        asr.provider(),
        asr.model(),
        ttsProvider(),
        ttsModel(),
        ttsVoice(),
        maxRecordingSeconds(),
        maxUploadBytes);
  }

  private String ttsProvider() {
    return tts != null && hasText(tts.provider()) ? tts.provider() : "unconfigured";
  }

  private String ttsModel() {
    return tts != null && hasText(tts.model()) ? tts.model() : "unconfigured";
  }

  private String ttsVoice() {
    return tts != null && hasText(tts.voice()) ? tts.voice() : "server-default";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record Asr(
      String provider,
      String baseUrl,
      String apiKey,
      String model,
      Duration timeout) {}

  public record Tts(
      String provider,
      String model,
      String voice,
      Duration timeout) {}
}
