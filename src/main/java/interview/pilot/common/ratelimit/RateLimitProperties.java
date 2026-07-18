package interview.pilot.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(int llmMaxConcurrency, Duration llmLease) {
  public RateLimitProperties {
    if (llmMaxConcurrency < 1) {
      throw new IllegalArgumentException("LLM max concurrency must be positive");
    }
    if (llmLease == null || llmLease.isZero() || llmLease.isNegative()) {
      throw new IllegalArgumentException("LLM lease must be positive");
    }
  }
}
