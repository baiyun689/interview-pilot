package interview.pilot.common.ratelimit;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import interview.pilot.auth.application.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class RateLimitAspect {
  private final RateLimiter limiter;
  private final HttpServletRequest request;
  private final CurrentUserProvider currentUser;

  public RateLimitAspect(
      RateLimiter limiter, HttpServletRequest request, CurrentUserProvider currentUser) {
    this.limiter = limiter;
    this.request = request;
    this.currentUser = currentUser;
  }

  @Around("@annotation(interview.pilot.common.ratelimit.RateLimit) || "
      + "@annotation(interview.pilot.common.ratelimit.RateLimits)")
  public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    for (RateLimit rule : method.getAnnotationsByType(RateLimit.class)) {
      check(rule, method, joinPoint.getArgs());
    }
    return joinPoint.proceed();
  }

  private void check(RateLimit rule, Method method, Object[] arguments) {
    String ruleBucket = method.getDeclaringClass().getSimpleName().toLowerCase(java.util.Locale.ROOT)
        + "." + method.getName().toLowerCase(java.util.Locale.ROOT);
    String bucket = switch (rule.scope()) {
      case IP -> "ip:" + ruleBucket + ":" + sha256(request.getRemoteAddr());
      case SESSION -> "session:" + ruleBucket + ":" + Arrays.stream(arguments)
          .filter(UUID.class::isInstance)
          .map(UUID.class::cast)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("Session rate limit requires a UUID argument"));
      case USER -> "user:" + ruleBucket + ":" + currentUser.require().userId();
    };
    try {
      if (!limiter.allowFixedWindow(
          bucket, rule.capacity(), Duration.ofSeconds(rule.windowSeconds()))) {
        throw new RateLimitedException();
      }
    } catch (RateLimitedException exception) {
      throw exception;
    } catch (RateLimitBackendException exception) {
      if (rule.expensive()) throw new RateLimitUnavailableException();
      // Read-only paths remain available while Redis is unavailable.
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
