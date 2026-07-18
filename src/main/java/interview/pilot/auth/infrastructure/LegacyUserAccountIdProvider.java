package interview.pilot.auth.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class LegacyUserAccountIdProvider {
  private static final long LEGACY_USER_ACCOUNT_ID = 1L;

  public long currentUserAccountId() {
    return LEGACY_USER_ACCOUNT_ID;
  }
}
