package interview.pilot.async.idempotency;

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
public class RedisProcessingClaim implements ProcessingClaim {
  static final String KEY_PREFIX = "interview-pilot:processing:";

  private static final String COMPLETE_SCRIPT =
      readScript("redis/complete_processing_claim.lua");
  private static final String RELEASE_SCRIPT =
      readScript("redis/release_processing_claim.lua");
  private static final String CLEAR_TERMINAL_SCRIPT =
      readScript("redis/clear_terminal_processing_claim.lua");

  private final RedissonClient redis;

  public RedisProcessingClaim(RedissonClient redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> acquire(String businessKey, Duration ttl) {
    String token = UUID.randomUUID().toString();
    boolean acquired = redis.getBucket(key(businessKey), StringCodec.INSTANCE)
        .setIfAbsent(token, ttl);
    return acquired ? Optional.of(token) : Optional.empty();
  }

  @Override
  public boolean complete(String businessKey, String token, Duration ttl) {
    Long completed = redis.getScript(StringCodec.INSTANCE).eval(
        RScript.Mode.READ_WRITE,
        COMPLETE_SCRIPT,
        RScript.ReturnType.LONG,
        List.of(key(businessKey)),
        token,
        ttl.toMillis());
    return completed == 1L;
  }

  @Override
  public boolean release(String businessKey, String token) {
    Long released = redis.getScript(StringCodec.INSTANCE).eval(
        RScript.Mode.READ_WRITE,
        RELEASE_SCRIPT,
        RScript.ReturnType.LONG,
        List.of(key(businessKey)),
        token);
    return released == 1L;
  }

  @Override
  public ClearResult clearTerminal(String businessKey) {
    Long result = redis.getScript(StringCodec.INSTANCE).eval(
        RScript.Mode.READ_WRITE, CLEAR_TERMINAL_SCRIPT, RScript.ReturnType.LONG,
        List.of(key(businessKey)));
    return switch (result.intValue()) {
      case 0 -> ClearResult.ABSENT;
      case 1 -> ClearResult.CLEARED;
      default -> ClearResult.ACTIVE;
    };
  }

  private String key(String businessKey) {
    return KEY_PREFIX + businessKey;
  }

  private static String readScript(String location) {
    try {
      return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot load Redis processing claim script", exception);
    }
  }
}
