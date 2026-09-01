package interview.pilot.voice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class VoicePropertiesTest {

  private static VoiceProperties disabledGarbage() {
    return new VoiceProperties(
        false, null, -1, Duration.ofSeconds(-5), Duration.ZERO, null, null);
  }

  private static VoiceProperties enabledComplete() {
    return new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60)),
        new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30)));
  }

  @Test
  void disabledAcceptsGarbageCredentialsLimitsAndMissingSections() {
    var properties = disabledGarbage();

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.asrConfigured()).isFalse();
    assertThat(properties.ttsConfigured()).isFalse();
  }

  @Test
  void disabledWithValidDefaultsStillReportsCapabilities() {
    var properties = new VoiceProperties(
        false, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60)),
        new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30)));

    assertThat(properties.maxRecordingSeconds()).isEqualTo(300);
    assertThat(properties.maxUploadBytes()).isEqualTo(8_388_608);
    assertThat(properties.asrConfigured()).isFalse();
    assertThat(properties.ttsConfigured()).isFalse();
  }

  @Test
  void enabledAcceptsCompleteConfigurationAndDerivesSeconds() {
    var properties = enabledComplete();

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.maxRecordingSeconds()).isEqualTo(300);
    assertThat(properties.asrConfigured()).isTrue();
    assertThat(properties.ttsConfigured()).isTrue();
  }

  @Test
  void enabledRejectsBothBlankApiKeyAndWorkspaceId() {
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60)),
        new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API key");
  }

  @Test
  void enabledAcceptsWorkspaceIdInsteadOfApiKey() {
    var properties = new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "ws-123", "", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60)),
        null);

    assertThat(properties.asrConfigured()).isTrue();
    assertThat(properties.ttsConfigured()).isFalse();
  }

  @Test
  void enabledRejectsBlankAsrProvider() {
    assertThatThrownBy(() -> withAsr(
        new VoiceProperties.Asr(
            " ", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ASR provider");
  }

  @Test
  void enabledRejectsBlankAsrModel() {
    assertThatThrownBy(() -> withAsr(
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test", "  ",
            Duration.ofSeconds(60))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ASR model");
  }

  @Test
  void enabledRejectsBlankAsrBaseUrl() {
    assertThatThrownBy(() -> withAsr(
        new VoiceProperties.Asr(
            "dashscope", "", "", "sk-test", "fun-asr-flash-2026-06-15",
            Duration.ofSeconds(60))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base URL");
  }

  @Test
  void enabledRejectsMissingAsrSection() {
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ASR");
  }

  @Test
  void enabledRejectsNonPositiveUploadBytes() {
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 0, Duration.ofMinutes(5), Duration.ofDays(7),
        asrComplete(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("upload bytes");
  }

  @Test
  void enabledRejectsNonPositiveRecordingDuration() {
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ZERO, Duration.ofDays(7),
        asrComplete(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recording duration");
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(-5), Duration.ofDays(7),
        asrComplete(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recording duration");
  }

  @Test
  void enabledRejectsNonPositiveRetention() {
    assertThatThrownBy(() -> new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(-1),
        asrComplete(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention");
  }

  @Test
  void enabledAllowsUnconfiguredTtsAsDegradablePlayback() {
    var properties = new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asrComplete(), null);

    assertThat(properties.asrConfigured()).isTrue();
    assertThat(properties.ttsConfigured()).isFalse();
  }

  @Test
  void ttsConfiguredRequiresProviderModelAndVoice() {
    assertThat(ttsComplete().ttsConfigured()).isTrue();
    var blankVoice = new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asrComplete(), new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", " ", Duration.ofSeconds(30)));
    assertThat(blankVoice.ttsConfigured()).isFalse();
  }

  @Test
  void snapshotCarriesConfiguredProviderModelAndLimits() {
    var snapshot = ttsComplete().toSnapshot();

    assertThat(snapshot.schemaVersion()).isEqualTo(1);
    assertThat(snapshot.asrProvider()).isEqualTo("dashscope");
    assertThat(snapshot.asrModel()).isEqualTo("fun-asr-flash-2026-06-15");
    assertThat(snapshot.ttsProvider()).isEqualTo("dashscope");
    assertThat(snapshot.ttsModel()).isEqualTo("cosyvoice-v3-flash");
    assertThat(snapshot.voice()).isEqualTo("longanyang");
    assertThat(snapshot.maxRecordingSeconds()).isEqualTo(300);
    assertThat(snapshot.maxUploadBytes()).isEqualTo(8_388_608);
  }

  @Test
  void snapshotUsesServerDefaultVoiceWhenTtsUnconfigured() {
    var snapshot = new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asrComplete(), null).toSnapshot();

    assertThat(snapshot.ttsProvider()).isEqualTo("unconfigured");
    assertThat(snapshot.ttsModel()).isEqualTo("unconfigured");
    assertThat(snapshot.voice()).isEqualTo("server-default");
  }

  @Test
  void snapshotDerivesSecondsFromRecordingDuration() {
    var snapshot = new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(2), Duration.ofDays(7),
        asrComplete(), null).toSnapshot();

    assertThat(snapshot.maxRecordingSeconds()).isEqualTo(120);
  }

  private static VoiceProperties withAsr(VoiceProperties.Asr asr) {
    return new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asr, null);
  }

  private static VoiceProperties.Asr asrComplete() {
    return new VoiceProperties.Asr(
        "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test", "fun-asr-flash-2026-06-15",
        Duration.ofSeconds(60));
  }

  private static VoiceProperties ttsComplete() {
    return new VoiceProperties(
        true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asrComplete(),
        new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30)));
  }
}
