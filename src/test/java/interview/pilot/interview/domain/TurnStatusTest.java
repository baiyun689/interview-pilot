package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TurnStatusTest {
  @Test
  void coversEveryTransitionIncludingIdempotentRetryFromFailed() {
    for (TurnStatus source : TurnStatus.values()) {
      Set<TurnStatus> allowedTargets = switch (source) {
        case ASKED -> Set.of(TurnStatus.PROCESSING);
        case PROCESSING -> Set.of(TurnStatus.COMPLETED, TurnStatus.FAILED);
        case COMPLETED -> Set.of();
        case FAILED -> Set.of(TurnStatus.PROCESSING);
      };

      for (TurnStatus target : TurnStatus.values()) {
        assertThat(source.canTransitionTo(target))
            .as("%s -> %s", source, target)
            .isEqualTo(allowedTargets.contains(target));
      }
    }
  }
}
