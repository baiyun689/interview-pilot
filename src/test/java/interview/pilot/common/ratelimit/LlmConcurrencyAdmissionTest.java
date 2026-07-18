package interview.pilot.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import interview.pilot.ai.provider.AiProviderProperties;

class LlmConcurrencyAdmissionTest {
  @Test
  void distinguishesCapacityFromBackendOutage() {
    var limiter = mock(RateLimiter.class);
    var admission = admission(limiter);
    when(limiter.acquireLlm(1, Duration.ofSeconds(3))).thenReturn(Optional.empty());
    assertThatThrownBy(admission::acquire).isInstanceOf(RateLimitedException.class);

    when(limiter.acquireLlm(1, Duration.ofSeconds(3)))
        .thenThrow(new RateLimitBackendException());
    assertThatThrownBy(admission::acquire).isInstanceOf(RateLimitUnavailableException.class);
  }

  @Test
  void rejectsLeaseThatCannotCoverProviderTimeout() {
    var limiter = mock(RateLimiter.class);
    assertThatThrownBy(() -> new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(1)), providers()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer");
  }

  @Test
  void leaseMustStrictlyExceedConnectPlusReadTimeoutBudget() {
    var limiter = mock(RateLimiter.class);
    var providers = providers(Duration.ofSeconds(30));

    assertThatThrownBy(() -> new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(31)), providers))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(60)), providers))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(61)), providers))
        .doesNotThrowAnyException();
    assertThatCode(() -> new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(90)), providers))
        .doesNotThrowAnyException();
  }

  @Test
  void overflowingProviderBudgetIsRejectedAsInvalidConfiguration() {
    var limiter = mock(RateLimiter.class);

    assertThatThrownBy(() -> new LlmConcurrencyAdmission(
        limiter,
        new RateLimitProperties(1, Duration.ofSeconds(Long.MAX_VALUE)),
        providers(Duration.ofSeconds(Long.MAX_VALUE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout budget");
  }

  private LlmConcurrencyAdmission admission(RateLimiter limiter) {
    return new LlmConcurrencyAdmission(
        limiter, new RateLimitProperties(1, Duration.ofSeconds(3)), providers());
  }

  private AiProviderProperties providers() {
    return providers(Duration.ofSeconds(1));
  }

  private AiProviderProperties providers(Duration timeout) {
    var provider = new AiProviderProperties.Provider(
        "Test", URI.create("https://example.invalid"), "key", "model", true,
        timeout);
    return new AiProviderProperties("test", Map.of("test", provider), 2);
  }
}
