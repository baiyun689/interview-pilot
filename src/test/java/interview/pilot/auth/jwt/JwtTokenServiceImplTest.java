package interview.pilot.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import io.jsonwebtoken.JwtException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceImplTest {

  @Mock RedissonClient redisson;
  @Mock UserAccountRepository accountRepository;

  // refresh() 需要真实的序列化/反序列化 refresh token 存储数据
  ObjectMapper objectMapper = new ObjectMapper();

  JwtProperties properties = new JwtProperties(
      Duration.ofMinutes(15), Duration.ofDays(7),
      "this-is-a-test-hmac-secret-with-at-least-32-chars!!");

  JwtTokenServiceImpl service;

  CurrentUser user = new CurrentUser(
      1L, UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
      "test@example.com", "Test User");

  @BeforeEach
  void setUp() {
    service = new JwtTokenServiceImpl(redisson, properties, objectMapper, accountRepository);
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
        redisson, shortLived, objectMapper, fixedClock, accountRepository);
    String token = shortService.issueAccessToken(user);
    // Now advance the clock: create a new service with clock advanced past TTL
    Clock advancedClock = Clock.fixed(
        fixedClock.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC);
    JwtTokenServiceImpl advancedService = new JwtTokenServiceImpl(
        redisson, shortLived, objectMapper, advancedClock, accountRepository);
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
    when(redisson.<String>getBucket(anyString())).thenReturn(mock(RBucket.class));
    RefreshToken token1 = service.issueRefreshToken(user);
    RefreshToken token2 = service.issueRefreshToken(user);
    assertThat(token1.value()).isNotEmpty();
    assertThat(token2.value()).isNotEmpty();
    assertThat(token1.value()).isNotEqualTo(token2.value());
    assertThat(token1.tokenFamily()).isNotEqualTo(token2.tokenFamily());
  }

  @Test
  void refreshIssuesAccessTokenWithRealDatabaseId() {
    // 捕获 issueRefreshToken 存入 Redis 的真实 JSON,模拟存储中的 refresh token
    AtomicReference<String> storedJson = new AtomicReference<>();
    RBucket<String> refreshBucket = mock(RBucket.class);
    when(redisson.<String>getBucket(anyString())).thenReturn(refreshBucket);
    when(refreshBucket.get()).thenAnswer(invocation -> storedJson.get());
    doAnswer(invocation -> {
      storedJson.set(invocation.getArgument(0));
      return null;
    }).when(refreshBucket).set(anyString(), any(Duration.class));

    RefreshToken refreshToken = service.issueRefreshToken(user);
    String raw = refreshToken.value();
    String family = refreshToken.tokenFamily();

    RBucket<String> usedBucket = mock(RBucket.class);
    when(redisson.<String>getBucket("interview-pilot:used_refresh:" + family)).thenReturn(usedBucket);
    when(usedBucket.get()).thenReturn(null);

    UserAccountEntity account = mock(UserAccountEntity.class);
    when(account.getId()).thenReturn(1L);
    when(account.getUserId()).thenReturn(user.userId());
    when(account.getEmail()).thenReturn(user.email());
    when(account.getDisplayName()).thenReturn(user.displayName());
    when(account.getStatus()).thenReturn(UserStatus.ACTIVE);
    when(accountRepository.findByUserId(user.userId())).thenReturn(Optional.of(account));

    TokenPair pair = service.refresh(raw);

    Optional<CurrentUser> parsed = service.verifyAccessToken(pair.accessToken());
    assertThat(parsed).isPresent();
    assertThat(parsed.get().databaseId()).isEqualTo(1L);
    assertThat(parsed.get().userId()).isEqualTo(user.userId());
    assertThat(pair.refreshToken().tokenFamily()).isEqualTo(family);
  }

  @Test
  void rapidRotationWithinReplayWindowStaysValid() {
    AtomicReference<String> storedJson = new AtomicReference<>();
    RBucket<String> refreshBucket = mock(RBucket.class);
    when(redisson.<String>getBucket(anyString())).thenReturn(refreshBucket);
    when(refreshBucket.get()).thenAnswer(invocation -> storedJson.get());
    doAnswer(invocation -> {
      storedJson.set(invocation.getArgument(0));
      return null;
    }).when(refreshBucket).set(anyString(), any(Duration.class));

    RefreshToken issued = service.issueRefreshToken(user);
    String family = issued.tokenFamily();

    AtomicReference<String> usedValue = new AtomicReference<>();
    RBucket<String> usedBucket = mock(RBucket.class);
    when(redisson.<String>getBucket("interview-pilot:used_refresh:" + family))
        .thenReturn(usedBucket);
    when(usedBucket.get()).thenAnswer(invocation -> usedValue.get());
    doAnswer(invocation -> {
      usedValue.set(invocation.getArgument(0));
      return null;
    }).when(usedBucket).set(anyString(), any(Duration.class));

    UserAccountEntity account = mock(UserAccountEntity.class);
    when(account.getId()).thenReturn(1L);
    when(account.getUserId()).thenReturn(user.userId());
    when(account.getEmail()).thenReturn(user.email());
    when(account.getDisplayName()).thenReturn(user.displayName());
    when(account.getStatus()).thenReturn(UserStatus.ACTIVE);
    when(accountRepository.findByUserId(user.userId())).thenReturn(Optional.of(account));

    TokenPair first = service.refresh(issued.value());
    TokenPair second = service.refresh(first.refreshToken().value());

    assertThat(first.refreshToken().tokenFamily()).isEqualTo(family);
    assertThat(second.refreshToken().tokenFamily()).isEqualTo(family);
    assertThat(second.accessToken()).isNotBlank();
  }

  @Test
  void refreshRejectsAnImmediatelyReplayedSameToken() {
    AtomicReference<String> storedJson = new AtomicReference<>();
    RBucket<String> refreshBucket = mock(RBucket.class);
    when(redisson.<String>getBucket(anyString())).thenReturn(refreshBucket);
    when(refreshBucket.get()).thenAnswer(invocation -> storedJson.get());
    doAnswer(invocation -> {
      storedJson.set(invocation.getArgument(0));
      return null;
    }).when(refreshBucket).set(anyString(), any(Duration.class));

    RefreshToken issued = service.issueRefreshToken(user);
    String family = issued.tokenFamily();

    AtomicReference<String> usedValue = new AtomicReference<>();
    RBucket<String> usedBucket = mock(RBucket.class);
    when(redisson.<String>getBucket("interview-pilot:used_refresh:" + family))
        .thenReturn(usedBucket);
    when(usedBucket.get()).thenAnswer(invocation -> usedValue.get());
    doAnswer(invocation -> {
      usedValue.set(invocation.getArgument(0));
      return null;
    }).when(usedBucket).set(anyString(), any(Duration.class));

    UserAccountEntity account = mock(UserAccountEntity.class);
    when(account.getId()).thenReturn(1L);
    when(account.getUserId()).thenReturn(user.userId());
    when(account.getEmail()).thenReturn(user.email());
    when(account.getDisplayName()).thenReturn(user.displayName());
    when(account.getStatus()).thenReturn(UserStatus.ACTIVE);
    when(accountRepository.findByUserId(user.userId())).thenReturn(Optional.of(account));

    TokenPair first = service.refresh(issued.value());
    // 同一 token 在窗口内再次出现才算 replay(轮换后的新 token 不算)
    usedValue.set(first.refreshToken().value());

    assertThatThrownBy(() -> service.refresh(first.refreshToken().value()))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("replay");
  }

  @Test
  void refreshRejectsDisabledAccount() {
    AtomicReference<String> storedJson = new AtomicReference<>();
    RBucket<String> refreshBucket = mock(RBucket.class);
    when(redisson.<String>getBucket(anyString())).thenReturn(refreshBucket);
    when(refreshBucket.get()).thenAnswer(invocation -> storedJson.get());
    doAnswer(invocation -> {
      storedJson.set(invocation.getArgument(0));
      return null;
    }).when(refreshBucket).set(anyString(), any(Duration.class));

    RefreshToken refreshToken = service.issueRefreshToken(user);

    RBucket<String> usedBucket = mock(RBucket.class);
    when(redisson.<String>getBucket("interview-pilot:used_refresh:" + refreshToken.tokenFamily()))
        .thenReturn(usedBucket);
    when(usedBucket.get()).thenReturn(null);

    UserAccountEntity account = mock(UserAccountEntity.class);
    when(account.getStatus()).thenReturn(UserStatus.DISABLED);
    when(accountRepository.findByUserId(user.userId())).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.refresh(refreshToken.value()))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void refreshRejectsMissingAccount() {
    AtomicReference<String> storedJson = new AtomicReference<>();
    RBucket<String> refreshBucket = mock(RBucket.class);
    when(redisson.<String>getBucket(anyString())).thenReturn(refreshBucket);
    when(refreshBucket.get()).thenAnswer(invocation -> storedJson.get());
    doAnswer(invocation -> {
      storedJson.set(invocation.getArgument(0));
      return null;
    }).when(refreshBucket).set(anyString(), any(Duration.class));

    RefreshToken refreshToken = service.issueRefreshToken(user);

    RBucket<String> usedBucket = mock(RBucket.class);
    when(redisson.<String>getBucket("interview-pilot:used_refresh:" + refreshToken.tokenFamily()))
        .thenReturn(usedBucket);
    when(usedBucket.get()).thenReturn(null);

    when(accountRepository.findByUserId(user.userId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.refresh(refreshToken.value()))
        .isInstanceOf(JwtException.class);
  }
}
