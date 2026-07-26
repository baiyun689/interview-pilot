package interview.pilot.auth.jwt;

import java.util.UUID;

public record TokenPairResponse(
    String accessToken,
    UUID userId,
    String email,
    String displayName) {

  public static TokenPairResponse from(TokenPair pair) {
    return new TokenPairResponse(
        pair.accessToken(),
        pair.user().userId(),
        pair.user().email(),
        pair.user().displayName());
  }
}
