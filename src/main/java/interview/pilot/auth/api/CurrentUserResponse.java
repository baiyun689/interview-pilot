package interview.pilot.auth.api;

import java.util.UUID;

import interview.pilot.auth.application.CurrentUser;

public record CurrentUserResponse(UUID userId, String email, String displayName) {
  public static CurrentUserResponse from(CurrentUser user) {
    return new CurrentUserResponse(user.userId(), user.email(), user.displayName());
  }
}
