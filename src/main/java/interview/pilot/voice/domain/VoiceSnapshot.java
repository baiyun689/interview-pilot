package interview.pilot.voice.domain;

public record VoiceSnapshot(
    int schemaVersion,
    String asrProvider,
    String asrModel,
    String ttsProvider,
    String ttsModel,
    String voice,
    int maxRecordingSeconds,
    long maxUploadBytes) {

  public static final int SCHEMA_VERSION = 1;

  public VoiceSnapshot {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("schemaVersion must be 1");
    }
    asrProvider = required(asrProvider, 64, "asrProvider");
    asrModel = required(asrModel, 128, "asrModel");
    ttsProvider = required(ttsProvider, 64, "ttsProvider");
    ttsModel = required(ttsModel, 128, "ttsModel");
    voice = required(voice, 128, "voice");
    if (maxRecordingSeconds <= 0) {
      throw new IllegalArgumentException("maxRecordingSeconds must be positive");
    }
    if (maxUploadBytes <= 0) {
      throw new IllegalArgumentException("maxUploadBytes must be positive");
    }
  }

  public static VoiceSnapshot of(
      int schemaVersion, String asrProvider, String asrModel,
      String ttsProvider, String ttsModel, String voice,
      int maxRecordingSeconds, long maxUploadBytes) {
    return new VoiceSnapshot(
        schemaVersion, asrProvider, asrModel, ttsProvider, ttsModel, voice,
        maxRecordingSeconds, maxUploadBytes);
  }

  private static String required(String value, int max, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " must be non-blank and at most " + max);
    }
    return normalized;
  }
}
