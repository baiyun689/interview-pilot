package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedInterviewFlowTest {
  @Test
  void exposesTheThreeProductSizesAsFixedPhaseBudgets() {
    assertSize(InterviewSize.QUICK, 6, 1, 2, 2, 1, 2, 1, 1);
    assertSize(InterviewSize.STANDARD, 9, 1, 3, 3, 2, 3, 2, 1);
    assertSize(InterviewSize.DEEP, 12, 1, 4, 4, 3, 4, 2, 2);
  }

  @Test
  void followUpsAreOnlyAvailableWhenRequiredMainQuestionsStillFit() {
    assertThat(InterviewSize.STANDARD.canAskFollowUp(
        InterviewPhase.PROJECT_EXPERIENCE, 1, 1)).isTrue();
    assertThat(InterviewSize.STANDARD.canAskFollowUp(
        InterviewPhase.PROJECT_EXPERIENCE, 1, 2)).isFalse();
    assertThat(InterviewSize.DEEP.canAskFollowUp(
        InterviewPhase.PROJECT_EXPERIENCE, 1, 2)).isTrue();
    assertThat(InterviewSize.DEEP.canAskFollowUp(
        InterviewPhase.PROJECT_EXPERIENCE, 1, 3)).isFalse();
    assertThat(InterviewSize.DEEP.canAskFollowUp(
        InterviewPhase.FUNDAMENTALS, 1, 1)).isFalse();
  }

  private void assertSize(
      InterviewSize size, int total, int self, int fundamentals, int project, int scenario,
      int minimumFundamentals, int minimumProject, int minimumScenario) {
    assertThat(size.totalTurns()).isEqualTo(total);
    assertThat(size.turnBudget(InterviewPhase.SELF_INTRODUCTION)).isEqualTo(self);
    assertThat(size.turnBudget(InterviewPhase.FUNDAMENTALS)).isEqualTo(fundamentals);
    assertThat(size.turnBudget(InterviewPhase.PROJECT_EXPERIENCE)).isEqualTo(project);
    assertThat(size.turnBudget(InterviewPhase.SCENARIO_TRADEOFF)).isEqualTo(scenario);
    assertThat(size.minimumMainQuestions(InterviewPhase.FUNDAMENTALS))
        .isEqualTo(minimumFundamentals);
    assertThat(size.minimumMainQuestions(InterviewPhase.PROJECT_EXPERIENCE))
        .isEqualTo(minimumProject);
    assertThat(size.minimumMainQuestions(InterviewPhase.SCENARIO_TRADEOFF))
        .isEqualTo(minimumScenario);
  }
}
