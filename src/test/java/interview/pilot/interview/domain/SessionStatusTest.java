package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStatusTest {
  @Test
  void allowsOnlyForwardBusinessTransitions() {
    assertThat(SessionStatus.CREATED.canTransitionTo(SessionStatus.INTERVIEWING)).isTrue();
    assertThat(SessionStatus.INTERVIEWING.canTransitionTo(SessionStatus.EVALUATING)).isTrue();
    assertThat(SessionStatus.EVALUATING.canTransitionTo(SessionStatus.COMPLETED)).isTrue();
    assertThat(SessionStatus.COMPLETED.canTransitionTo(SessionStatus.INTERVIEWING)).isFalse();
  }

  @Test
  void permitsFailureFromActiveStatesOnly() {
    assertThat(SessionStatus.INTERVIEWING.canTransitionTo(SessionStatus.FAILED)).isTrue();
    assertThat(SessionStatus.COMPLETED.canTransitionTo(SessionStatus.FAILED)).isFalse();
  }
}
