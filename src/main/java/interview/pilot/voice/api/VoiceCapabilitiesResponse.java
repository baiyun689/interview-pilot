package interview.pilot.voice.api;

import java.util.List;

public record VoiceCapabilitiesResponse(
    boolean enabled,
    List<String> supportedMimeTypes,
    int maxRecordingSeconds,
    long maxUploadBytes,
    boolean ttsEnabled) {

  public VoiceCapabilitiesResponse {
    supportedMimeTypes = List.copyOf(supportedMimeTypes);
  }
}
