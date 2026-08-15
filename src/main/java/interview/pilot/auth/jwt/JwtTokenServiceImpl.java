package interview.pilot.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class JwtTokenServiceImpl implements JwtTokenService {

  private static final String REFRESH_PREFIX = "interview-pilot:refresh:";
  private static final String USED_PREFIX = "interview-pilot:used_refresh:";
  private static final Duration REPLAY_WINDOW = Duration.ofSeconds(30);

  private final RedissonClient redisson;
  private final JwtProperties properties;
  private final SecretKey hmacKey;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final UserAccountRepository accountRepository;

  // 生产环境构造函数 (由 Spring @Component 调用)
  @Autowired
  public JwtTokenServiceImpl(RedissonClient redisson, JwtProperties properties,
                              ObjectMapper objectMapper,
                              UserAccountRepository accountRepository) {
    this(redisson, properties, objectMapper, Clock.systemUTC(), accountRepository);
  }

  // Test constructor
  JwtTokenServiceImpl(RedissonClient redisson, JwtProperties properties,
                       ObjectMapper objectMapper, Clock clock,
                       UserAccountRepository accountRepository) {
    this.redisson = redisson;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.accountRepository = accountRepository;
    this.hmacKey = Keys.hmacShaKeyFor(properties.hmacSecret().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String issueAccessToken(CurrentUser user) {
    Instant now = clock.instant();
    return Jwts.builder()
        .subject(user.userId().toString())
        .claim("dbId", user.databaseId())
        .claim("email", user.email())
        .claim("displayName", user.displayName())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.accessTokenTtl())))
        .signWith(hmacKey)
        .compact();
  }

  @Override
  public RefreshToken issueRefreshToken(CurrentUser user) {
    String family = UUID.randomUUID().toString();
    RefreshToken token = RefreshToken.create(user, family,
        properties.refreshTokenTtl().toSeconds());
    RBucket<String> bucket = redisson.getBucket(REFRESH_PREFIX + token.value());
    bucket.set(toJson(token), properties.refreshTokenTtl());
    return token;
  }

  @Override
  public Optional<CurrentUser> verifyAccessToken(String token) {
    try {
      Claims claims = Jwts.parser()
          .clock(() -> Date.from(clock.instant()))
          .verifyWith(hmacKey)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      Long dbId = claims.get("dbId", Long.class);
      return Optional.of(new CurrentUser(
          dbId,
          UUID.fromString(claims.getSubject()),
          claims.get("email", String.class),
          claims.get("displayName", String.class)));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  @Override
  public TokenPair refresh(String rawToken) {
    RBucket<String> bucket = redisson.getBucket(REFRESH_PREFIX + rawToken);
    String json = bucket.get();
    if (json == null) {
      throw new JwtException("Refresh token not found or expired");
    }

    RefreshToken stored = parseStored(json, rawToken);
    bucket.delete();

    String usedKey = USED_PREFIX + stored.tokenFamily();
    RBucket<String> usedBucket = redisson.getBucket(usedKey);
    if (usedBucket.get() != null) {
      log.warn("Refresh token replay detected for userId={} family={}",
          stored.userId(), stored.tokenFamily());
      throw new JwtException("Token replay detected; all sessions revoked");
    }
    usedBucket.set(rawToken, REPLAY_WINDOW);

    UserAccountEntity account = accountRepository.findByUserId(stored.userId())
        .filter(acc -> acc.getStatus() == UserStatus.ACTIVE)
        .orElseThrow(() -> new JwtException(
            "User account not found or disabled; refresh rejected"));

    CurrentUser user = new CurrentUser(account.getId(), account.getUserId(),
        account.getEmail(), account.getDisplayName());
    String accessToken = issueAccessToken(user);
    RefreshToken newRefresh = RefreshToken.create(user, stored.tokenFamily(),
        properties.refreshTokenTtl().toSeconds());
    RBucket<String> newBucket = redisson.getBucket(REFRESH_PREFIX + newRefresh.value());
    newBucket.set(toJson(newRefresh), properties.refreshTokenTtl());

    return new TokenPair(accessToken, newRefresh, user);
  }

  @Override
  public void revoke(String rawToken) {
    RBucket<String> bucket = redisson.getBucket(REFRESH_PREFIX + rawToken);
    String json = bucket.get();
    if (json != null) {
      bucket.delete();
    }
  }

  private static final class StoredRefreshToken {
    public String userId;
    public String email;
    public String displayName;
    public String tokenFamily;
  }

  private RefreshToken parseStored(String json, String tokenValue) {
    try {
      StoredRefreshToken s = objectMapper.readValue(json, StoredRefreshToken.class);
      return new RefreshToken(
          tokenValue,
          UUID.fromString(s.userId),
          s.email,
          s.displayName,
          s.tokenFamily,
          clock.instant(),
          clock.instant().plus(properties.refreshTokenTtl()));
    } catch (Exception e) {
      throw new JwtException("Invalid refresh token data", e);
    }
  }

  private String toJson(RefreshToken token) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "userId", token.userId().toString(),
          "email", token.email(),
          "displayName", token.displayName(),
          "tokenFamily", token.tokenFamily()));
    } catch (Exception e) {
      throw new JwtException("Failed to serialize refresh token", e);
    }
  }
}
