package interview.pilot.resume.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ResumeStatusTest {
  @Test
  void coversEveryTransitionIncludingManualRetryFromFailed() {
    for (ResumeStatus source : ResumeStatus.values()) {
      Set<ResumeStatus> allowedTargets = switch (source) {
        case PENDING -> Set.of(ResumeStatus.ANALYZING);
        case ANALYZING -> Set.of(ResumeStatus.READY, ResumeStatus.FAILED);
        case READY -> Set.of();
        case FAILED -> Set.of(ResumeStatus.PENDING);
      };

      for (ResumeStatus target : ResumeStatus.values()) {
        assertThat(source.canTransitionTo(target))
            .as("%s -> %s", source, target)
            .isEqualTo(allowedTargets.contains(target));
      }
    }
  }
}
