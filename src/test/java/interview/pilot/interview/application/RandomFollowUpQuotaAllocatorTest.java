package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.InterviewPhase;

class RandomFollowUpQuotaAllocatorTest {
  private final RandomFollowUpQuotaAllocator allocator = new RandomFollowUpQuotaAllocator();

  @Test
  void allocatesOneOrTwoFollowUpsForEveryMainQuestionPhaseButNotSelfIntroduction() {
    assertThat(allocator.allocate(InterviewPhase.SELF_INTRODUCTION)).isZero();
    for (InterviewPhase phase : new InterviewPhase[] {
        InterviewPhase.FUNDAMENTALS,
        InterviewPhase.PROJECT_EXPERIENCE,
        InterviewPhase.SCENARIO_TRADEOFF}) {
      for (int sample = 0; sample < 100; sample++) {
        assertThat(allocator.allocate(phase)).isBetween(1, 2);
      }
    }
  }
}
