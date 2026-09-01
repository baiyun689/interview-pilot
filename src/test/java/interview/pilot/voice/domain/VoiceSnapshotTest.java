package interview.pilot.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class VoiceSnapshotTest {
  private static final int VERSION = 1;
  private static final String ASR_PROVIDER = "dashscope";
  private static final String ASR_MODEL = "fun-asr-flash-2026-06-15";
  private static final String TTS_PROVIDER = "dashscope";
  private static final String TTS_MODEL = "cosyvoice-v3-flash";
  private static final String VOICE = "server-default";
  private static final int MAX_RECORDING_SECONDS = 300;
  private static final long MAX_UPLOAD_BYTES = 8_388_608;

  @Test
  void acceptsTheDocumentedExampleSnapshot() {
    var snapshot = VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES);

    assertThat(snapshot.schemaVersion()).isEqualTo(1);
    assertThat(snapshot.asrProvider()).isEqualTo("dashscope");
    assertThat(snapshot.asrModel()).isEqualTo("fun-asr-flash-2026-06-15");
    assertThat(snapshot.ttsProvider()).isEqualTo("dashscope");
    assertThat(snapshot.ttsModel()).isEqualTo("cosyvoice-v3-flash");
    assertThat(snapshot.voice()).isEqualTo("server-default");
    assertThat(snapshot.maxRecordingSeconds()).isEqualTo(300);
    assertThat(snapshot.maxUploadBytes()).isEqualTo(8_388_608);
  }

  @Test
  void rejectsWrongSchemaVersion() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        2, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void rejectsMissingAsrProvider() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, null, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asrProvider");
  }

  @Test
  void rejectsBlankAsrModel() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, "  ", TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asrModel");
  }

  @Test
  void rejectsMissingTtsProvider() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, null, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttsProvider");
  }

  @Test
  void rejectsBlankTtsModel() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, " ", VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttsModel");
  }

  @Test
  void rejectsBlankVoice() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, "",
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("voice");
  }

  @Test
  void rejectsNonPositiveRecordingSeconds() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        0, MAX_UPLOAD_BYTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRecordingSeconds");
  }

  @Test
  void rejectsNonPositiveUploadBytes() {
    assertThatThrownBy(() -> VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxUploadBytes");
  }

  @Test
  void normalizesProviderStringsAndKeepsLimitsImmutable() {
    var snapshot = VoiceSnapshot.of(
        VERSION, "  dashscope  ", ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES);

    assertThat(snapshot.asrProvider()).isEqualTo("dashscope");
  }

  @Test
  void roundTripsThroughJsonForTheVoiceSnapshotColumn() throws Exception {
    var snapshot = VoiceSnapshot.of(
        VERSION, ASR_PROVIDER, ASR_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE,
        MAX_RECORDING_SECONDS, MAX_UPLOAD_BYTES);
    ObjectMapper objectMapper = new ObjectMapper();

    String json = objectMapper.writeValueAsString(snapshot);
    assertThat(json).contains("\"schemaVersion\":1", "\"maxUploadBytes\":8388608");

    assertThat(objectMapper.readValue(json, VoiceSnapshot.class)).isEqualTo(snapshot);
  }
}
