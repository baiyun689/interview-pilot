package interview.pilot.async.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisProcessingClaimIT {
  private static final String KEY_PREFIX = "interview-pilot:processing:";

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(6379);

  private static RedissonClient redis;
  private static ProcessingClaim claim;

  @BeforeAll
  static void createClaim() {
    var config = new Config();
    config.useSingleServer().setAddress(
        "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    redis = Redisson.create(config);
    claim = new RedisProcessingClaim(redis);
  }

  @AfterAll
  static void closeRedisClient() {
    if (redis != null) {
      redis.shutdown();
    }
  }

  @Test
  void acquisitionStoresAnExpiringOpaqueToken() {
    String token = claim.acquire("resume:42", Duration.ofSeconds(10)).orElseThrow();

    assertThat(redis.getBucket(KEY_PREFIX + "resume:42", StringCodec.INSTANCE).get())
        .isEqualTo(token);
    assertThat(redis.getBucket(KEY_PREFIX + "resume:42", StringCodec.INSTANCE).remainTimeToLive())
        .isBetween(1L, Duration.ofSeconds(10).toMillis());
  }

  @Test
  void acquisitionCanBeRetriedAfterTheOwnerExpires() throws InterruptedException {
    assertThat(claim.acquire("resume:expiring", Duration.ofMillis(150))).isPresent();

    TimeUnit.MILLISECONDS.sleep(250);

    assertThat(claim.acquire("resume:expiring", Duration.ofSeconds(5))).isPresent();
  }

  @Test
  void concurrentAcquisitionCreatesExactlyOneOwner() throws Exception {
    int contenders = 12;
    var ready = new CountDownLatch(contenders);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(contenders);
    try {
      List<Callable<Optional<String>>> attempts = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        attempts.add(() -> {
          ready.countDown();
          start.await(5, TimeUnit.SECONDS);
          return claim.acquire("resume:concurrent", Duration.ofSeconds(10));
        });
      }

      var futures = attempts.stream().map(executor::submit).toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var owners = new HashSet<String>();
      for (var future : futures) {
        future.get(5, TimeUnit.SECONDS).ifPresent(owners::add);
      }
      assertThat(owners).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void staleOwnerCannotReleaseTheCurrentOwner() {
    String first = claim.acquire("resume:release", Duration.ofMinutes(10)).orElseThrow();
    redis.getBucket(KEY_PREFIX + "resume:release", StringCodec.INSTANCE).set("new-owner");

    assertThat(claim.release("resume:release", first)).isFalse();
    assertThat(redis.getBucket(KEY_PREFIX + "resume:release", StringCodec.INSTANCE).get())
        .isEqualTo("new-owner");
  }

  @Test
  void staleOwnerCannotCompleteOverTheCurrentOwner() {
    String first = claim.acquire("resume:complete-stale", Duration.ofMinutes(10)).orElseThrow();
    redis.getBucket(KEY_PREFIX + "resume:complete-stale", StringCodec.INSTANCE).set("new-owner");

    assertThat(claim.complete("resume:complete-stale", first, Duration.ofHours(1))).isFalse();
    assertThat(redis.getBucket(KEY_PREFIX + "resume:complete-stale", StringCodec.INSTANCE).get())
        .isEqualTo("new-owner");
  }

  @Test
  void currentOwnerCanCompleteAndSetTheDoneTtl() {
    String token = claim.acquire("resume:complete", Duration.ofMinutes(10)).orElseThrow();

    assertThat(claim.complete("resume:complete", token, Duration.ofSeconds(30))).isTrue();
    assertThat(redis.getBucket(KEY_PREFIX + "resume:complete", StringCodec.INSTANCE).get())
        .isEqualTo("done:" + token);
    assertThat(redis.getBucket(KEY_PREFIX + "resume:complete", StringCodec.INSTANCE).remainTimeToLive())
        .isBetween(1L, Duration.ofSeconds(30).toMillis());
  }

  @Test
  void currentOwnerCanReleaseItsClaim() {
    String token = claim.acquire("resume:release-current", Duration.ofMinutes(10)).orElseThrow();

    assertThat(claim.release("resume:release-current", token)).isTrue();
    assertThat(redis.getBucket(KEY_PREFIX + "resume:release-current", StringCodec.INSTANCE)
        .isExists()).isFalse();
  }

  @Test
  void manualRetryClearsOnlyDoneOrAbsentMarkersAndNeverAnActiveOwner() {
    assertThat(claim.clearTerminal("retry:absent"))
        .isEqualTo(ProcessingClaim.ClearResult.ABSENT);
    String token = claim.acquire("retry:done", Duration.ofMinutes(1)).orElseThrow();
    claim.complete("retry:done", token, Duration.ofMinutes(1));
    assertThat(claim.clearTerminal("retry:done"))
        .isEqualTo(ProcessingClaim.ClearResult.CLEARED);
    assertThat(redis.getBucket(KEY_PREFIX + "retry:done", StringCodec.INSTANCE).isExists())
        .isFalse();

    String active = claim.acquire("retry:active", Duration.ofMinutes(1)).orElseThrow();
    assertThat(claim.clearTerminal("retry:active"))
        .isEqualTo(ProcessingClaim.ClearResult.ACTIVE);
    assertThat(redis.getBucket(KEY_PREFIX + "retry:active", StringCodec.INSTANCE).get())
        .isEqualTo(active);
  }
}
