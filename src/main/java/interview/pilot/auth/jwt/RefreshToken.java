package interview.pilot.auth.jwt;

import java.time.Instant;
import java.util.UUID;
import interview.pilot.auth.application.CurrentUser;

public record RefreshToken(
    String value,
    UUID userId,
    String email,
    String displayName,
    String tokenFamily,
    Instant issuedAt,
    Instant expiresAt) {

  public static RefreshToken create(CurrentUser user, String tokenFamily,
                                     long ttlSeconds) {
    Instant now = Instant.now();
    return new RefreshToken(
        UUID.randomUUID().toString(), user.userId(), user.email(), user.displayName(),
        tokenFamily, now, now.plusSeconds(ttlSeconds));
  }
}
