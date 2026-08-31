package interview.pilot.interview.voice;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class VoiceWebSocketConfig implements WebSocketConfigurer {
  private final VoiceInterviewWebSocketHandler handler;

  public VoiceWebSocketConfig(VoiceInterviewWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws/voice-interviews/{sessionId}")
        .setAllowedOriginPatterns("http://localhost:*", "https://localhost:*");
  }
}
