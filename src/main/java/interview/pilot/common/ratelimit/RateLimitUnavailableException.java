package interview.pilot.common.ratelimit;

import org.springframework.http.HttpStatus;

import interview.pilot.common.exception.BusinessException;

public class RateLimitUnavailableException extends BusinessException {
  public RateLimitUnavailableException() {
    super("RATE_LIMIT_UNAVAILABLE", "Rate limiting is temporarily unavailable",
        HttpStatus.SERVICE_UNAVAILABLE);
  }
}
