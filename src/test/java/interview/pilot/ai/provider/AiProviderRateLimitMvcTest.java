package interview.pilot.ai.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.ForwardedHeaderFilter;

import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.common.observability.TraceIdFilter;
import interview.pilot.common.ratelimit.LlmConcurrencyAdmission;
import interview.pilot.common.ratelimit.RateLimitAspect;
import interview.pilot.common.ratelimit.RateLimitBackendException;
import interview.pilot.common.ratelimit.RateLimitProperties;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;

@WebMvcTest(
    controllers = AiProviderController.class,
    properties = {
        "app.ai.default-provider=test",
        "app.ai.structured-max-attempts=2",
        "app.ai.providers.test.display-name=Test",
        "app.ai.providers.test.base-url=https://provider.invalid",
        "app.ai.providers.test.api-key=test-key",
        "app.ai.providers.test.model=test-model",
        "app.ai.providers.test.enabled=true",
        "app.ai.providers.test.timeout=1s",
        "app.rate-limit.llm-max-concurrency=1",
        "app.rate-limit.llm-lease=3s"
    })
@EnableConfigurationProperties({AiProviderProperties.class, RateLimitProperties.class})
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Import({
    AiProviderService.class,
    AiProviderRegistry.class,
    LlmConcurrencyAdmission.class,
    RateLimitAspect.class,
    ForwardedHeaderFilter.class,
    TraceIdFilter.class,
    GlobalExceptionHandler.class
})
class AiProviderRateLimitMvcTest {
  private static final String TRACE_ID = "123e4567-e89b-12d3-a456-426614174000";

  @Autowired MockMvc mvc;
  @Autowired Environment environment;
  @MockitoBean AiSettingRepository settings;
  @MockitoBean RateLimiter limiter;
  @MockitoBean AiMetrics metrics;
  @MockitoBean CurrentUserProvider currentUserProvider;

  @BeforeEach
  void setUp() {
    reset(limiter, settings, metrics, currentUserProvider);
    var setting = new AiSettingEntity();
    setting.setProviderId("test");
    when(settings.findById(1L)).thenReturn(Optional.of(setting));
    when(currentUserProvider.require()).thenReturn(
        new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User"));
  }

  @Test
  void productionFixedIpRuleAllowsNthAndReturns429ForNPlusOneWithSameTrace() throws Exception {
    var count = new AtomicInteger();
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenAnswer(invocation -> count.incrementAndGet() <= 120);

    for (int request = 1; request <= 120; request++) {
      mvc.perform(get("/api/ai/providers").header(TraceIdFilter.HEADER, TRACE_ID))
          .andExpect(status().isOk());
    }
    mvc.perform(get("/api/ai/providers").header(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void productionProviderChainMapsGlobalCapacityDenialTo429() throws Exception {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(limiter.acquireLlm(1, Duration.ofSeconds(3))).thenReturn(Optional.empty());

    mvc.perform(post("/api/ai/providers/{id}/test", "test")
            .header(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void productionProviderChainMapsGlobalBackendOutageTo503() throws Exception {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(limiter.acquireLlm(1, Duration.ofSeconds(3)))
        .thenThrow(new RateLimitBackendException());

    mvc.perform(post("/api/ai/providers/{id}/test", "test")
            .header(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void productionReadonlyRuleFailsOpenDuringBackendOutage() throws Exception {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenThrow(new RateLimitBackendException());

    mvc.perform(get("/api/ai/providers").header(TraceIdFilter.HEADER, TRACE_ID))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceIdFilter.HEADER, TRACE_ID));
  }

  @Test
  void trustedForwardedAddressDrivesThePerIpBucket() throws Exception {
    assertThat(environment.getProperty("server.forward-headers-strategy"))
        .isEqualTo("framework");
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

    mvc.perform(get("/api/ai/providers")
            .header("X-Forwarded-For", "203.0.113.41"))
        .andExpect(status().isOk());

    var bucket = ArgumentCaptor.forClass(String.class);
    verify(limiter).allowFixedWindow(bucket.capture(), anyInt(), any(Duration.class));
    assertThat(bucket.getValue())
        .isEqualTo("ip:aiprovidercontroller.list:" + sha256("203.0.113.41"));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
