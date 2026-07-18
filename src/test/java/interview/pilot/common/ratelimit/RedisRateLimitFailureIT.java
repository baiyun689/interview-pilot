package interview.pilot.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisRateLimitFailureIT {
  @Test
  void realRedisOutageFailsClosedForExpensiveAndOpenForReadonly() {
    var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);
    redis.start();
    RedissonClient client = null;
    try {
      var config = new Config();
      config.useSingleServer()
          .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
          .setConnectTimeout(500)
          .setTimeout(500)
          .setRetryAttempts(0);
      client = Redisson.create(config);
      var request = new MockHttpServletRequest();
      request.setRemoteAddr("198.51.100.7");
      var proxy = new AspectJProxyFactory(new Api());
      proxy.addAspect(new RateLimitAspect(new RedisRateLimiter(client), request));
      Api api = proxy.getProxy();

      redis.stop();

      assertThatThrownBy(api::expensive).isInstanceOf(RateLimitUnavailableException.class);
      assertThat(api.readonly()).isEqualTo("ok");
    } finally {
      if (client != null) client.shutdown();
      if (redis.isRunning()) redis.stop();
    }
  }

  static class Api {
    @RateLimit(scope = RateLimitScope.IP, capacity = 1, expensive = true)
    public String expensive() { return "ok"; }

    @RateLimit(scope = RateLimitScope.IP, capacity = 1)
    public String readonly() { return "ok"; }
  }
}
