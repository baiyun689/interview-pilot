package interview.pilot.auth.application;

import java.util.UUID;

public record AuthenticatedUser(
    Long databaseId,
    UUID userId,
    String email,
    String displayName) {

  public CurrentUser currentUser() {
    return new CurrentUser(databaseId, userId, email, displayName);
  }
}
