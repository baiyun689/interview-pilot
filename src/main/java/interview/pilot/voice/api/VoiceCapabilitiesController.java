package interview.pilot.voice.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.VoiceMimeTypes;

/**
 * Public capability constants and the on/off switch only: never credentials, keys,
 * or provider addresses.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceCapabilitiesController {

  private final VoiceProperties properties;

  public VoiceCapabilitiesController(VoiceProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/capabilities")
  public VoiceCapabilitiesResponse capabilities() {
    return new VoiceCapabilitiesResponse(
        properties.enabled(),
        VoiceMimeTypes.SUPPORTED,
        properties.maxRecordingSeconds(),
        Math.max(0, properties.maxUploadBytes()),
        properties.ttsConfigured());
  }
}
