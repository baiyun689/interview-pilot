package interview.pilot.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.jwt")
public record JwtProperties(
    @DefaultValue("15m") Duration accessTokenTtl,
    @DefaultValue("7d") Duration refreshTokenTtl,
    String hmacSecret) {

  public JwtProperties {
    if (hmacSecret == null || hmacSecret.length() < 32) {
      throw new IllegalArgumentException(
          "app.jwt.hmac-secret must be at least 32 characters");
    }
  }
}
