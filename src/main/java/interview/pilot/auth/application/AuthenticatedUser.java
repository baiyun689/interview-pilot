package interview.pilot.auth.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AuthenticatedUser(
    Long databaseId,
    UUID userId,
    String email,
    String displayName,
    String password,
    boolean enabled) implements UserDetails {
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of();
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  public CurrentUser currentUser() {
    return new CurrentUser(databaseId, userId, email, displayName);
  }
}
