package interview.pilot.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyUserAccountIdProviderTest {
  @Test
  void resolvesTheDisabledLegacyAccountDuringTheAuthenticationTransition() {
    assertThat(new LegacyUserAccountIdProvider().currentUserAccountId()).isEqualTo(1L);
  }
}
