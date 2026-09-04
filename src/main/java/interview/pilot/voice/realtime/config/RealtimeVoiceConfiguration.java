package interview.pilot.voice.realtime.config;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import interview.pilot.auth.jwt.JwtTokenService;
import interview.pilot.interview.application.FixedAnswerService;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.realtime.asr.DashScopeStreamingAsrClient;
import interview.pilot.voice.realtime.asr.StreamingAsrClient;
import interview.pilot.voice.realtime.handler.RealtimeVoiceWebSocketHandler;
import interview.pilot.voice.realtime.orchestrator.VoiceTurnOrchestrator;
import interview.pilot.voice.realtime.security.VoiceHandshakeInterceptor;
import interview.pilot.voice.realtime.tts.DashScopeRealtimeTtsClient;
import interview.pilot.voice.realtime.tts.RealtimeTtsClient;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the realtime voice pipeline only when the voice master switch is on
 * ({@code app.voice.enabled=true}) and the realtime channel is not explicitly turned off
 * ({@code app.voice.realtime.enabled}, default true). Keeping the whole stack conditional means
 * text interviews and the existing file-recording voice pipeline are completely unaffected when
 * this feature is off or when DashScope credentials are absent.
 *
 * <p>Note: beans are declared flatly on this single configuration class on purpose. A static
 * nested {@code @Configuration} class is independently picked up by component scanning (a static
 * nested class is an independent candidate), which would bypass the type-level condition and run
 * ahead of {@link EnableConfigurationProperties}. One class + one combined condition avoids that
 * trap.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(RealtimeVoiceProperties.class)
@ConditionalOnExpression("'${app.voice.enabled:false}' == 'true' and '${app.voice.realtime.enabled:true}' == 'true'")
public class RealtimeVoiceConfiguration {

  @Bean
  StreamingAsrClient streamingAsrClient(RealtimeVoiceProperties properties,
                                        VoiceProperties voiceProperties) {
    return new DashScopeStreamingAsrClient(properties, voiceProperties.asr().apiKey());
  }

  @Bean
  RealtimeTtsClient realtimeTtsClient(RealtimeVoiceProperties properties,
                                      VoiceProperties voiceProperties) {
    // The deployment uses one DashScope key for both ASR and TTS.
    return new DashScopeRealtimeTtsClient(properties, voiceProperties.asr().apiKey());
  }

  @Bean
  VoiceTurnOrchestrator voiceTurnOrchestrator(FixedAnswerService answers,
                                              InterviewSessionRepository sessions,
                                              InterviewTurnRepository turns,
                                              RealtimeTtsClient tts) {
    return new VoiceTurnOrchestrator(answers, sessions, turns, tts);
  }

  @Bean
  VoiceHandshakeInterceptor voiceHandshakeInterceptor(JwtTokenService jwtTokenService,
                                                      InterviewSessionRepository sessions) {
    return new VoiceHandshakeInterceptor(jwtTokenService, sessions);
  }

  @Bean
  RealtimeVoiceWebSocketHandler realtimeVoiceWebSocketHandler(
      RealtimeVoiceProperties properties,
      StreamingAsrClient asr,
      VoiceTurnOrchestrator orchestrator,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    return new RealtimeVoiceWebSocketHandler(
        properties, asr, orchestrator, objectMapper, meterRegistry);
  }

  @Bean
  WebSocketConfigurer voiceWebSocketConfigurer(RealtimeVoiceProperties properties,
                                               RealtimeVoiceWebSocketHandler handler,
                                               VoiceHandshakeInterceptor interceptor) {
    // The client connects to "{path}/{sessionId}" and the handshake interceptor reads the session
    // id from the last path segment, so register a single-segment wildcard — registering only
    // properties.path() itself would 404 every real connection.
    String base = properties.path().endsWith("/")
        ? properties.path().substring(0, properties.path().length() - 1) : properties.path();
    String pattern = base + "/*";
    return new WebSocketConfigurer() {
      @Override
      public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, pattern)
            .addInterceptors(interceptor)
            .setAllowedOriginPatterns("*");
      }
    };
  }

  /**
   * Raises Tomcat's inbound WS decode buffers above the default 8&nbsp;KiB. Each uplink audio frame
   * is base64 PCM carried as one TEXT message; at 16&nbsp;kHz/16-bit that easily exceeds 8&nbsp;KiB,
   * which otherwise makes Tomcat close the socket with code 1009 ("text message too big ... does not
   * support partial messages") on the very first frame — the client then reconnects in a loop. Size
   * it from the same business cap as the application-level frame check, with headroom for JSON.
   *
   * <p>A {@link ServletServerContainerFactoryBean} fetches {@code jakarta.websocket.server.
   * ServerContainer} from the ServletContext during its lifecycle start and throws when the
   * attribute is absent — which is exactly the case in {@code @SpringBootTest(webEnvironment=MOCK)},
   * whose Mock ServletContext hosts no real WS container, taking the whole test context (and every
   * IT sharing it) down. {@link WebSocketBufferTuner} instead probes for the attribute at start and
   * only tunes a real embedded container, silently no-oping under tests.
   */
  @Bean
  WebSocketBufferTuner voiceWebSocketBufferTuner(RealtimeVoiceProperties properties) {
    int appCapBytes = properties.conversation().maxMessageKb() * 1024;
    int bufferBytes = Math.max(512 * 1024, appCapBytes * 2);
    return new WebSocketBufferTuner(bufferBytes);
  }

  /**
   * Sets the JSR-356 container's inbound buffers on a real embedded container during context start;
   * a no-op when no {@link ServerContainer} attribute is present (e.g. mocked web test contexts).
   */
  static final class WebSocketBufferTuner implements SmartLifecycle, ServletContextAware {

    private static final String SERVER_CONTAINER_ATTRIBUTE = "jakarta.websocket.server.ServerContainer";

    private final int bufferBytes;
    private ServletContext servletContext;
    private volatile boolean running;

    WebSocketBufferTuner(int bufferBytes) {
      this.bufferBytes = bufferBytes;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
      this.servletContext = servletContext;
    }

    @Override
    public void start() {
      if (servletContext == null) {
        return;
      }
      Object container = servletContext.getAttribute(SERVER_CONTAINER_ATTRIBUTE);
      if (container instanceof ServerContainer serverContainer) {
        serverContainer.setDefaultMaxTextMessageBufferSize(bufferBytes);
        serverContainer.setDefaultMaxBinaryMessageBufferSize(bufferBytes);
      }
      running = true;
    }

    @Override
    public void stop() {
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }
  }
}
