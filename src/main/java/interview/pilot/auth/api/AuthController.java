package interview.pilot.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.auth.application.AuthService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.jwt.JwtTokenService;
import interview.pilot.auth.jwt.RefreshToken;
import interview.pilot.auth.jwt.TokenPair;
import interview.pilot.auth.jwt.TokenPairResponse;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final JwtTokenService jwtService;

  public AuthController(AuthService authService, JwtTokenService jwtService) {
    this.authService = authService;
    this.jwtService = jwtService;
  }

  @PostMapping("/register")
  public ResponseEntity<TokenPairResponse> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletResponse response) {
    CurrentUser user = authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(issueTokens(response, user));
  }

  @PostMapping("/login")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  public TokenPairResponse login(
      @Valid @RequestBody LoginRequest request,
      HttpServletResponse response) {
    CurrentUser user = authService.authenticate(request.email(), request.password());
    return issueTokens(response, user);
  }

  @PostMapping("/refresh")
  @RateLimit(scope = RateLimitScope.IP, capacity = 30)
  public TokenPairResponse refresh(
      @CookieValue("refresh_token") String rawToken,
      HttpServletResponse response) {
    TokenPair pair = jwtService.refresh(rawToken);
    setRefreshCookie(response, pair.refreshToken());
    return TokenPairResponse.from(pair);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(value = "refresh_token", required = false) String rawToken,
      HttpServletResponse response) {
    if (rawToken != null) jwtService.revoke(rawToken);
    clearRefreshCookie(response);
    return ResponseEntity.noContent().build();
  }

  private TokenPairResponse issueTokens(HttpServletResponse response, CurrentUser user) {
    String accessToken = jwtService.issueAccessToken(user);
    RefreshToken refreshToken = jwtService.issueRefreshToken(user);
    setRefreshCookie(response, refreshToken);
    return new TokenPairResponse(
        accessToken, user.userId(), user.email(), user.displayName());
  }

  private void setRefreshCookie(HttpServletResponse response, RefreshToken token) {
    Cookie cookie = new Cookie("refresh_token", token.value());
    cookie.setHttpOnly(true);
    cookie.setSecure(false);
    cookie.setPath("/api/auth");
    cookie.setMaxAge((int) java.time.Duration.ofDays(7).toSeconds());
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);
  }

  private void clearRefreshCookie(HttpServletResponse response) {
    Cookie cookie = new Cookie("refresh_token", "");
    cookie.setHttpOnly(true);
    cookie.setSecure(false);
    cookie.setPath("/api/auth");
    cookie.setMaxAge(0);
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);
  }
}
