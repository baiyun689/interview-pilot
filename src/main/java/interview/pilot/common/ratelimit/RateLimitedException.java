package interview.pilot.common.ratelimit;

import org.springframework.http.HttpStatus;

import interview.pilot.common.exception.BusinessException;

public class RateLimitedException extends BusinessException {
  public RateLimitedException() {
    super("RATE_LIMITED", "Too many requests", HttpStatus.TOO_MANY_REQUESTS);
  }
}
