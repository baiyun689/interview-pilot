package interview.pilot.common.ratelimit;

public class RateLimitBackendException extends RuntimeException {
  public RateLimitBackendException() {
    super("Rate limit backend unavailable");
  }

  public RateLimitBackendException(Throwable cause) {
    super("Rate limit backend unavailable", cause);
  }
}
