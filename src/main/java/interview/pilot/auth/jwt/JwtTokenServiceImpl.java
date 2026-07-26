package interview.pilot.auth.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import interview.pilot.auth.application.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JwtTokenServiceImpl implements JwtTokenService {

  private static final String REFRESH_PREFIX = "interview-pilot:refresh:";
  private static final String USED_PREFIX = "interview-pilot:used_refresh:";
  private static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);

  private final RedissonClient redisson;
  private final JwtProperties properties;
  private final SecretKey hmacKey;

  public JwtTokenServiceImpl(RedissonClient redisson, JwtProperties properties) {
    this.redisson = redisson;
    this.properties = properties;
    this.hmacKey = Keys.hmacShaKeyFor(properties.hmacSecret().getBytes());
  }

  @Override
  public String issueAccessToken(CurrentUser user) {
    Instant now = Instant.now();
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

    CurrentUser user = new CurrentUser(null, stored.userId(),
        stored.email(), stored.displayName());
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

  private RefreshToken parseStored(String json, String tokenValue) {
    try {
      int uStart = json.indexOf("\"userId\":\"") + 10;
      int uEnd = json.indexOf("\"", uStart);
      int eStart = json.indexOf("\"email\":\"") + 9;
      int eEnd = json.indexOf("\"", eStart);
      int dStart = json.indexOf("\"displayName\":\"") + 15;
      int dEnd = json.indexOf("\"", dStart);
      int fStart = json.indexOf("\"tokenFamily\":\"") + 15;
      int fEnd = json.indexOf("\"", fStart);
      return new RefreshToken(
          tokenValue,
          UUID.fromString(json.substring(uStart, uEnd)),
          json.substring(eStart, eEnd),
          json.substring(dStart, dEnd),
          json.substring(fStart, fEnd),
          Instant.now(),
          Instant.now().plus(properties.refreshTokenTtl()));
    } catch (Exception e) {
      throw new JwtException("Invalid refresh token data");
    }
  }

  private String toJson(RefreshToken token) {
    return "{\"userId\":\"" + token.userId()
        + "\",\"email\":\"" + escape(token.email())
        + "\",\"displayName\":\"" + escape(token.displayName())
        + "\",\"tokenFamily\":\"" + token.tokenFamily() + "\"}";
  }

  private String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
