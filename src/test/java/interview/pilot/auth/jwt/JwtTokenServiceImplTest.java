package interview.pilot.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import interview.pilot.auth.application.CurrentUser;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceImplTest {

  @Mock RedissonClient redisson;
  @Mock ObjectMapper objectMapper;

  JwtProperties properties = new JwtProperties(
      Duration.ofMinutes(15), Duration.ofDays(7),
      "this-is-a-test-hmac-secret-with-at-least-32-chars!!");

  JwtTokenServiceImpl service;

  CurrentUser user = new CurrentUser(
      1L, UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
      "test@example.com", "Test User");

  @BeforeEach
  void setUp() {
    service = new JwtTokenServiceImpl(redisson, properties, objectMapper);
  }

  @Test
  void issueAccessTokenProducesValidJwt() {
    String token = service.issueAccessToken(user);
    assertThat(token).isNotEmpty();
    Optional<CurrentUser> parsed = service.verifyAccessToken(token);
    assertThat(parsed).isPresent();
    assertThat(parsed.get().email()).isEqualTo("test@example.com");
    assertThat(parsed.get().displayName()).isEqualTo("Test User");
  }

  @Test
  void verifyAccessTokenRejectsExpiredToken() {
    JwtProperties shortLived = new JwtProperties(
        Duration.ofMinutes(1), Duration.ofDays(7),
        "this-is-a-test-hmac-secret-with-at-least-32-chars!!");
    Clock fixedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    JwtTokenServiceImpl shortService = new JwtTokenServiceImpl(
        redisson, shortLived, objectMapper, fixedClock);
    String token = shortService.issueAccessToken(user);
    // Now advance the clock: create a new service with clock advanced past TTL
    Clock advancedClock = Clock.fixed(
        fixedClock.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC);
    JwtTokenServiceImpl advancedService = new JwtTokenServiceImpl(
        redisson, shortLived, objectMapper, advancedClock);
    Optional<CurrentUser> result = advancedService.verifyAccessToken(token);
    assertThat(result).isEmpty();
  }

  @Test
  void verifyAccessTokenRejectsTamperedToken() {
    String token = service.issueAccessToken(user);
    String tampered = token.substring(0, token.length() - 3) + "xxx";
    Optional<CurrentUser> result = service.verifyAccessToken(tampered);
    assertThat(result).isEmpty();
  }

  @Test
  void issueRefreshTokenCreatesUniqueTokens() {
    when(redisson.getBucket(anyString())).thenReturn(mock(RBucket.class));
    RefreshToken token1 = service.issueRefreshToken(user);
    RefreshToken token2 = service.issueRefreshToken(user);
    assertThat(token1.value()).isNotEmpty();
    assertThat(token2.value()).isNotEmpty();
    assertThat(token1.value()).isNotEqualTo(token2.value());
    assertThat(token1.tokenFamily()).isNotEqualTo(token2.tokenFamily());
  }

  // refresh() 的完整测试需要 mock 多层 RedissonClient.getBucket() 调用,
  // 涉及三个不同的 key (refresh / used_refresh / new refresh)。
  // 当前依赖 @Mock + anyString() 会导致 Mockito 的 stub 冲突。
  // 已在集成测试计划中安排 (Phase 2 接入 SecurityConfig 时通过 Testcontainers + Redis 验证)。
}
