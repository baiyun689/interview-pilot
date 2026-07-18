package interview.pilot.common.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter implements RateLimiter {
  static final String KEY_PREFIX = "interview-pilot:rate-limit:";
  static final String LLM_KEY = KEY_PREFIX + "llm:global";
  private static final String SCRIPT = readScript();

  private final RedissonClient redis;

  public RedisRateLimiter(RedissonClient redis) {
    this.redis = redis;
  }

  @Override
  public boolean allowFixedWindow(String bucket, int capacity, Duration window) {
    requirePositive(capacity, window);
    try {
      Long result = eval(KEY_PREFIX + bucket, "fixed", capacity, window.toMillis());
      return result == 1L;
    } catch (RuntimeException exception) {
      throw new RateLimitBackendException(exception);
    }
  }

  @Override
  public Optional<String> acquireLlm(int capacity, Duration lease) {
    requirePositive(capacity, lease);
    String token = UUID.randomUUID().toString();
    try {
      Long result = eval(LLM_KEY, "lease_acquire", capacity, token, lease.toMillis());
      return result == 1L ? Optional.of(token) : Optional.empty();
    } catch (RuntimeException exception) {
      throw new RateLimitBackendException(exception);
    }
  }

  @Override
  public boolean releaseLlm(String token) {
    if (token == null || token.isBlank()) return false;
    try {
      return eval(LLM_KEY, "lease_release", token) == 1L;
    } catch (RuntimeException exception) {
      throw new RateLimitBackendException(exception);
    }
  }

  private Long eval(String key, Object... arguments) {
    return redis.getScript(StringCodec.INSTANCE).eval(
        RScript.Mode.READ_WRITE, SCRIPT, RScript.ReturnType.LONG, List.of(key), arguments);
  }

  private static void requirePositive(int capacity, Duration duration) {
    if (capacity < 1 || duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Rate limit capacity and duration must be positive");
    }
  }

  private static String readScript() {
    try {
      return new ClassPathResource("redis/rate_limit.lua")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot load Redis rate limit script", exception);
    }
  }
}
