package interview.pilot.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import interview.pilot.ai.provider.AiProviderProperties;

@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class LlmConcurrencyAdmission {
  private final RateLimiter limiter;
  private final RateLimitProperties limits;

  public LlmConcurrencyAdmission(
      RateLimiter limiter, RateLimitProperties limits, AiProviderProperties providers) {
    this.limiter = limiter;
    this.limits = limits;
    Duration longestTimeout = Duration.ZERO;
    for (AiProviderProperties.Provider provider : providers.providers().values()) {
      if (!provider.enabled()) continue;
      Duration timeout = provider.timeout();
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Enabled provider timeout must be positive");
      }
      if (timeout.compareTo(longestTimeout) > 0) longestTimeout = timeout;
    }
    Duration callBudget;
    try {
      callBudget = longestTimeout.multipliedBy(2);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Provider timeout budget is too large", exception);
    }
    if (limits.llmLease().compareTo(callBudget) <= 0) {
      throw new IllegalArgumentException(
          "LLM lease must be longer than the provider connect plus read timeout budget");
    }
  }

  public String acquire() {
    try {
      return limiter.acquireLlm(limits.llmMaxConcurrency(), limits.llmLease())
          .orElseThrow(RateLimitedException::new);
    } catch (RateLimitedException exception) {
      throw exception;
    } catch (RateLimitBackendException exception) {
      throw new RateLimitUnavailableException();
    }
  }

  public void release(String token) {
    try {
      limiter.releaseLlm(token);
    } catch (RateLimitBackendException ignored) {
      // A backend outage cannot safely release; Redis-time lease expiry recovers capacity.
    }
  }
}
