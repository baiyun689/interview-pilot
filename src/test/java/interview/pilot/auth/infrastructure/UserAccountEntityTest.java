package interview.pilot.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import interview.pilot.auth.domain.UserStatus;

class UserAccountEntityTest {
  @Test
  void registerCreatesActiveAccountWithUserId() {
    UserAccountEntity account = UserAccountEntity.register(
        "demo@example.com", "hashed-password", "Demo User");

    assertThat(account.getUserId()).isNotNull();
    assertThat(account.getEmail()).isEqualTo("demo@example.com");
    assertThat(account.getPasswordHash()).isEqualTo("hashed-password");
    assertThat(account.getDisplayName()).isEqualTo("Demo User");
    assertThat(account.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }
}
