package interview.pilot.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.auth.application.AuthService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final CurrentUserProvider currentUser;

  public AuthController(AuthService authService, CurrentUserProvider currentUser) {
    this.authService = authService;
    this.currentUser = currentUser;
  }

  @PostMapping("/register")
  public ResponseEntity<CurrentUserResponse> register(
      @Valid @RequestBody RegisterRequest request) {
    CurrentUser user = authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CurrentUserResponse.from(user));
  }

  @PostMapping("/login")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  public CurrentUserResponse login(@Valid @RequestBody LoginRequest request) {
    CurrentUser user = authService.authenticate(request.email(), request.password());
    return CurrentUserResponse.from(user);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout() {
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public CurrentUserResponse me() {
    return CurrentUserResponse.from(currentUser.require());
  }
}
