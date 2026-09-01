package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedInterviewFlowTest {
  @Test
  void exposesTheThreeProductSizesAsFixedPhaseBudgets() {
    assertSize(InterviewSize.QUICK, 6, 1, 2, 2, 1);
    assertSize(InterviewSize.STANDARD, 9, 1, 3, 3, 2);
    assertSize(InterviewSize.DEEP, 12, 1, 4, 4, 3);
  }

  @Test
  void everyMainQuestionPhaseAllowsFollowUpsWithoutConsumingMainQuestionBudget() {
    assertThat(InterviewPhase.SELF_INTRODUCTION.allowsFollowUp()).isFalse();
    assertThat(InterviewPhase.FUNDAMENTALS.allowsFollowUp()).isTrue();
    assertThat(InterviewPhase.PROJECT_EXPERIENCE.allowsFollowUp()).isTrue();
    assertThat(InterviewPhase.SCENARIO_TRADEOFF.allowsFollowUp()).isTrue();
  }

  private void assertSize(
      InterviewSize size, int total, int self, int fundamentals, int project, int scenario) {
    assertThat(size.totalMainQuestionCount()).isEqualTo(total);
    assertThat(size.mainQuestionCount(InterviewPhase.SELF_INTRODUCTION)).isEqualTo(self);
    assertThat(size.mainQuestionCount(InterviewPhase.FUNDAMENTALS)).isEqualTo(fundamentals);
    assertThat(size.mainQuestionCount(InterviewPhase.PROJECT_EXPERIENCE)).isEqualTo(project);
    assertThat(size.mainQuestionCount(InterviewPhase.SCENARIO_TRADEOFF)).isEqualTo(scenario);
  }
}
