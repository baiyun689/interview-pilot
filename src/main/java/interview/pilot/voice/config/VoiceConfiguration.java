package interview.pilot.voice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import interview.pilot.voice.application.SpeechRecognizer;
import interview.pilot.voice.application.SpeechSynthesizer;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.DashScopeSpeechRecognizer;
import interview.pilot.voice.infrastructure.DashScopeSpeechSynthesizer;
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

  /**
   * Production speech recognizer (plan §5.3). The HTTP client mirrors the AI provider
   * adapters: {@link RestClient} with connect/read timeouts from the ASR configuration; the
   * model name comes from {@code app.voice.asr.model} and is never hardcoded here.
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
  SpeechRecognizer speechRecognizer(VoiceProperties voice, VoiceMediaStore mediaStore) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(voice.asr().timeout());
    requestFactory.setReadTimeout(voice.asr().timeout());
    var restClient = RestClient.builder().requestFactory(requestFactory).build();
    return new DashScopeSpeechRecognizer(voice.asr(), restClient, mediaStore);
  }

  /**
   * Production speech synthesizer (plan §5.3/§11). Same HTTP client pattern as the
   * recognizer, with the TTS timeout (falling back to the ASR timeout when TTS is not
   * configured — TTS is a degradable capability, and the synthesizer is only reachable when
   * {@code ttsConfigured()} is true). The endpoint lives under the ASR base URL and
   * authenticates with the ASR api-key: the TTS configuration carries no credentials of its
   * own (Task 2 decision, confirmed by review; voice enabled always implies ASR configured).
   * The model and voice come from {@code app.voice.tts.*} at call time via the profile.
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
  SpeechSynthesizer speechSynthesizer(VoiceProperties voice) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    var timeout = voice.tts() != null ? voice.tts().timeout() : voice.asr().timeout();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    var restClient = RestClient.builder().requestFactory(requestFactory).build();
    return new DashScopeSpeechSynthesizer(voice.asr(), restClient);
  }
}
