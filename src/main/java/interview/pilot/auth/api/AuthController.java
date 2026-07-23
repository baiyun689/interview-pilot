package interview.pilot.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.auth.application.AuthService;
import interview.pilot.auth.application.AuthenticatedUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContexts;
  private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
  private final CurrentUserProvider currentUser;

  public AuthController(
      AuthService authService,
      AuthenticationManager authenticationManager,
      SecurityContextRepository securityContexts,
      SessionAuthenticationStrategy sessionAuthenticationStrategy,
      CurrentUserProvider currentUser) {
    this.authService = authService;
    this.authenticationManager = authenticationManager;
    this.securityContexts = securityContexts;
    this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    this.currentUser = currentUser;
  }

  @PostMapping("/register")
  public ResponseEntity<CurrentUserResponse> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(authenticate(request.email(), request.password(), servletRequest, servletResponse));
  }

  @PostMapping("/login")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  public CurrentUserResponse login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    return authenticate(request.email(), request.password(), servletRequest, servletResponse);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    SecurityContextHolder.clearContext();
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public CurrentUserResponse me() {
    return CurrentUserResponse.from(currentUser.require());
  }

  private CurrentUserResponse authenticate(
      String email,
      String password,
      HttpServletRequest request,
      HttpServletResponse response) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(email, password));
      sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      SecurityContextHolder.setContext(context);
      securityContexts.saveContext(context, request, response);
      return CurrentUserResponse.from(((AuthenticatedUser) authentication.getPrincipal()).currentUser());
    } catch (AuthenticationException exception) {
      throw new BusinessException(
          "AUTHENTICATION_FAILED", "Email or password is incorrect", HttpStatus.UNAUTHORIZED);
    }
  }
}
