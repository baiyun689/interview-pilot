package interview.pilot.voice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.FfprobeAudioProbe;
import interview.pilot.voice.storage.FileSystemVoiceMediaStore;
import interview.pilot.voice.storage.VoiceMediaStore;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfiguration {

  /**
   * Media beans exist only when voice is enabled: {@code VoiceProperties} then guarantees a
   * non-null files root, while a disabled voice must boot even with garbage configuration
   * (Task 2 review note).
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
  VoiceMediaStore voiceMediaStore(VoiceProperties voice, AudioProbe audioProbe) {
    return new FileSystemVoiceMediaStore(voice.filesRoot(), audioProbe);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
  AudioProbe audioProbe(ObjectMapper json) {
    return new FfprobeAudioProbe(json);
  }
}
