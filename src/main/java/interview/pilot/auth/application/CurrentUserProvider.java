package interview.pilot.auth.application;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {
  public CurrentUser require() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof CurrentUser user)) {
      throw new AuthenticationCredentialsNotFoundException("Authentication is required");
    }
    return user;
  }
}
