package interview.pilot.voice.realtime.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.jwt.JwtTokenService;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;

/**
 * Authenticates the WebSocket handshake and pins the connection to one owned interview session.
 *
 * <p>Browsers cannot set an Authorization header on a WebSocket, so the access token is supplied as
 * a {@code ?token=} query parameter (the client reuses the same short-lived JWT as the REST API).
 * The reference implementation trusted a path session id with no authentication — an IDOR risk;
 * here the token is verified and the session must belong to the same account before the upgrade is
 * accepted. The resolved {@link CurrentUser} and {@link UUID} are stashed in the WS attributes.
 */
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

  public static final String ATTR_CURRENT_USER = "realtime.currentUser";
  public static final String ATTR_SESSION_ID = "realtime.sessionId";

  private static final Logger log = LoggerFactory.getLogger(VoiceHandshakeInterceptor.class);

  private final JwtTokenService jwtTokenService;
  private final InterviewSessionRepository sessions;

  public VoiceHandshakeInterceptor(JwtTokenService jwtTokenService,
                                   InterviewSessionRepository sessions) {
    this.jwtTokenService = jwtTokenService;
    this.sessions = sessions;
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Map<String, Object> attributes) {
    Optional<String> token = extractToken(request);
    if (token.isEmpty()) {
      return reject(response, "missing token");
    }
    Optional<CurrentUser> user = jwtTokenService.verifyAccessToken(token.get());
    if (user.isEmpty()) {
      return reject(response, "invalid token");
    }
    UUID sessionId = extractSessionId(request);
    if (sessionId == null) {
      return reject(response, "missing session id");
    }
    boolean owned = sessions.findBySessionIdAndUserAccountId(sessionId, user.get().databaseId())
        .isPresent();
    if (!owned) {
      log.warn("Rejected voice WS handshake for session {} not owned by user {}",
          sessionId, user.get().databaseId());
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    attributes.put(ATTR_CURRENT_USER, user.get());
    attributes.put(ATTR_SESSION_ID, sessionId);
    return true;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                             WebSocketHandler wsHandler, Exception exception) {
    // no-op: authentication is fully resolved in beforeHandshake
  }

  private boolean reject(ServerHttpResponse response, String reason) {
    log.debug("Rejected voice WS handshake: {}", reason);
    response.setStatusCode(HttpStatus.UNAUTHORIZED);
    return false;
  }

  private Optional<String> extractToken(ServerHttpRequest request) {
    String query = request.getURI().getRawQuery();
    if (query == null) {
      // Fallback for clients that prefer the standard subprotocol header to carry the bearer.
      if (request instanceof ServletServerHttpRequest servletRequest) {
        String protocol = servletRequest.getServletRequest().getHeader("Sec-WebSocket-Protocol");
        if (protocol != null && !protocol.isBlank()) {
          // Form: "bearer.<jwt>" — keep the header name for compatibility, strip the prefix.
          String cleaned = protocol.contains(",") ? protocol.split(",")[0].trim() : protocol.trim();
          return Optional.of(cleaned.startsWith("bearer.") ? cleaned.substring(7) : cleaned);
        }
      }
      return Optional.empty();
    }
    for (Map.Entry<String, String> entry : parseQuery(query).entrySet()) {
      if ("token".equals(entry.getKey())) {
        return Optional.of(entry.getValue());
      }
    }
    return Optional.empty();
  }

  private UUID extractSessionId(ServerHttpRequest request) {
    try {
      String path = request.getURI().getPath();
      int slash = path.lastIndexOf('/');
      if (slash < 0 || slash == path.length() - 1) {
        return null;
      }
      return UUID.fromString(path.substring(slash + 1));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> params = new HashMap<>();
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = urlDecode(pair.substring(0, eq));
      String value = urlDecode(pair.substring(eq + 1));
      params.put(key, value);
    }
    return params;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
