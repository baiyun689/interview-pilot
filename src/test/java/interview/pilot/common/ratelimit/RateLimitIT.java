package interview.pilot.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.time.Duration;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;

import interview.pilot.ai.provider.AiProviderProperties;
import interview.pilot.ai.provider.AiProviderRegistry;
import interview.pilot.common.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Testcontainers
class RateLimitIT {
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static RedissonClient client;
  private static RedisRateLimiter limiter;

  @BeforeAll
  static void startClient() {
    var config = new Config();
    config.useSingleServer().setAddress(
        "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    client = Redisson.create(config);
    limiter = new RedisRateLimiter(client);
  }

  @AfterAll
  static void closeClient() {
    if (client != null) client.shutdown();
  }

  @BeforeEach
  void clearRedis() {
    client.getKeys().flushall();
  }

  @Test
  void fixedWindowsAreAtomicAndIndependentByIpAndSession() {
    Duration window = Duration.ofSeconds(10);

    assertThat(limiter.allowFixedWindow("ip:127.0.0.1", 2, window)).isTrue();
    assertThat(limiter.allowFixedWindow("ip:127.0.0.1", 2, window)).isTrue();
    assertThat(limiter.allowFixedWindow("ip:127.0.0.1", 2, window)).isFalse();
    assertThat(limiter.allowFixedWindow("session:00000000-0000-0000-0000-000000000001", 1, window))
        .isTrue();
    assertThat(limiter.allowFixedWindow("session:00000000-0000-0000-0000-000000000001", 1, window))
        .isFalse();
    assertThat(limiter.allowFixedWindow("session:00000000-0000-0000-0000-000000000002", 1, window))
        .isTrue();
  }

  @Test
  void twoConcurrentLlmCallsAtLimitOneAdmitExactlyOne() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> acquireTogether(ready, start));
      var second = pool.submit(() -> acquireTogether(ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(first.get(), second.get()).stream().filter(Optional::isPresent))
          .hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ownerReleaseLeaseExpiryAndStaleTokenAreSafe() throws Exception {
    String first = limiter.acquireLlm(1, Duration.ofMillis(150)).orElseThrow();
    assertThat(limiter.acquireLlm(1, Duration.ofSeconds(2))).isEmpty();
    TimeUnit.MILLISECONDS.sleep(250);
    String second = limiter.acquireLlm(1, Duration.ofSeconds(2)).orElseThrow();

    assertThat(limiter.releaseLlm(first)).isFalse();
    assertThat(limiter.acquireLlm(1, Duration.ofSeconds(2))).isEmpty();
    assertThat(limiter.releaseLlm(second)).isTrue();
    assertThat(limiter.acquireLlm(1, Duration.ofSeconds(2))).isPresent();
  }

  @Test
  void sharedProviderBoundaryAdmitsExactlyOneActualCallAndReleasesAfterSuccess() throws Exception {
    var wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
    try {
      wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
          .willReturn(aResponse().withFixedDelay(350)
              .withHeader("Content-Type", "application/json")
              .withBody(completionBody())));
      var meterRegistry = new SimpleMeterRegistry();
      var admission = new LlmConcurrencyAdmission(
          limiter, new RateLimitProperties(1, Duration.ofSeconds(3)),
          aiProperties(wireMock));
      var registry = new AiProviderRegistry(
          aiProperties(wireMock), admission, new AiMetrics(meterRegistry));
      var start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(2);
      try {
        var calls = java.util.List.of(
            pool.submit(() -> providerCall(registry, start)),
            pool.submit(() -> providerCall(registry, start)));
        start.countDown();
        var results = java.util.List.of(calls.get(0).get(), calls.get(1).get());

        assertThat(results).containsExactlyInAnyOrder("OK", "RATE_LIMITED");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
        assertThat(meterRegistry.get("interview_pilot.ai.calls").counter().count()).isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
    } finally {
      wireMock.stop();
    }
  }

  private String providerCall(AiProviderRegistry registry, CountDownLatch start) throws Exception {
    start.await(5, TimeUnit.SECONDS);
    try {
      return registry.generate("test", "system", "user").content();
    } catch (RateLimitedException exception) {
      return exception.code();
    }
  }

  private AiProviderProperties aiProperties(WireMockServer wireMock) {
    var provider = new AiProviderProperties.Provider(
        "Test", URI.create(wireMock.baseUrl()), "test-key", "test-model", true,
        Duration.ofSeconds(1));
    return new AiProviderProperties("test", Map.of("test", provider), 2);
  }

  private String completionBody() {
    return """
        {"id":"x","object":"chat.completion","created":1,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
         "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,
         "total_tokens":2}}
        """;
  }

  private static Optional<String> acquireTogether(CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await(5, TimeUnit.SECONDS);
    return limiter.acquireLlm(1, Duration.ofSeconds(5));
  }
}
