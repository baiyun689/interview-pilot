package interview.pilot.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;

class RateLimitAspectTest {
  private RateLimiter limiter;
  private SampleApi api;
  private CurrentUserProvider currentUserProvider;

  @BeforeEach
  void setUp() {
    limiter = mock(RateLimiter.class);
    currentUserProvider = mock(CurrentUserProvider.class);
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("192.0.2.10");
    var factory = new AspectJProxyFactory(new SampleApi());
    factory.addAspect(new RateLimitAspect(limiter, request, currentUserProvider));
    api = factory.getProxy();
  }

  @Test
  void expensivePathFailsClosedWhenRedisIsUnavailable() {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenThrow(new RateLimitBackendException());

    assertThatThrownBy(api::expensive).isInstanceOf(RateLimitUnavailableException.class);
  }

  @Test
  void readonlyPathFailsOpenWhenRedisIsUnavailable() {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenThrow(new RateLimitBackendException());

    assertThat(api.readonly()).isEqualTo("ok");
  }

  @Test
  void nPlusOneAndIndependentSessionQuotaAreEnforced() {
    when(limiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenReturn(true, false);
    assertThat(api.answer(UUID.randomUUID())).isEqualTo("ok");
    assertThatThrownBy(() -> api.answer(UUID.randomUUID()))
        .isInstanceOf(RateLimitedException.class);
  }

  @Test
  void ipBucketNeverContainsRawRemoteInput() {
    api.readonly();

    verify(limiter).allowFixedWindow(
        org.mockito.ArgumentMatchers.matches("ip:sampleapi.readonly:[0-9a-f]{64}"), eq(1),
        eq(Duration.ofSeconds(60)));
  }

  @Test
  void repeatableRulesEnforceIpAndSessionIndependently() {
    UUID sessionId = UUID.randomUUID();
    api.both(sessionId);

    verify(limiter).allowFixedWindow(
        org.mockito.ArgumentMatchers.matches("ip:sampleapi.both:[0-9a-f]{64}"), eq(2),
        eq(Duration.ofSeconds(60)));
    verify(limiter).allowFixedWindow(
        eq("session:sampleapi.both:" + sessionId), eq(3), eq(Duration.ofSeconds(60)));
  }

  @Test
  void differentEndpointRulesNeverShareTheSameIpCounter() {
    api.expensive();
    api.readonly();
    var keys = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(limiter, times(2)).allowFixedWindow(
        keys.capture(), anyInt(), any(Duration.class));

    assertThat(keys.getAllValues()).hasSize(2).doesNotHaveDuplicates();
  }

  @Test
  void userBucketIsDerivedFromTheAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    when(currentUserProvider.require()).thenReturn(
        new CurrentUser(7L, userId, "user@example.com", "User"));

    api.userScoped();

    verify(limiter).allowFixedWindow(
        eq("user:sampleapi.userscoped:" + userId), eq(4), eq(Duration.ofSeconds(60)));
  }

  static class SampleApi {
    @RateLimit(scope = RateLimitScope.IP, capacity = 1, expensive = true)
    public String expensive() { return "ok"; }

    @RateLimit(scope = RateLimitScope.IP, capacity = 1)
    public String readonly() { return "ok"; }

    @RateLimit(scope = RateLimitScope.SESSION, capacity = 1, expensive = true)
    public String answer(UUID sessionId) { return "ok"; }

    @RateLimit(scope = RateLimitScope.IP, capacity = 2, expensive = true)
    @RateLimit(scope = RateLimitScope.SESSION, capacity = 3, expensive = true)
    public String both(UUID sessionId) { return "ok"; }

    @RateLimit(scope = RateLimitScope.USER, capacity = 4, expensive = true)
    public String userScoped() { return "ok"; }
  }
}
