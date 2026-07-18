package interview.pilot.common.ratelimit;

import java.time.Duration;
import java.util.Optional;

public interface RateLimiter {
  boolean allowFixedWindow(String bucket, int capacity, Duration window);

  Optional<String> acquireLlm(int capacity, Duration lease);

  boolean releaseLlm(String token);
}
