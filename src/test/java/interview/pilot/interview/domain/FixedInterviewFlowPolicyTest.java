package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedInterviewFlowPolicyTest {
  private final FixedInterviewFlowPolicy flow = new FixedInterviewFlowPolicy();

  @Test
  void consumesEveryFollowUpBeforeAdvancingToTheNextMainQuestion() {
    assertThat(flow.next(InterviewSize.QUICK, progress(
        InterviewPhase.FUNDAMENTALS, 1, 0, 2)))
        .isEqualTo(FixedInterviewFlowPolicy.Decision.followUp(
            InterviewPhase.FUNDAMENTALS));

    assertThat(flow.next(InterviewSize.QUICK, progress(
        InterviewPhase.FUNDAMENTALS, 1, 1, 2)))
        .isEqualTo(FixedInterviewFlowPolicy.Decision.followUp(
            InterviewPhase.FUNDAMENTALS));

    assertThat(flow.next(InterviewSize.QUICK, progress(
        InterviewPhase.FUNDAMENTALS, 1, 2, 2)))
        .isEqualTo(FixedInterviewFlowPolicy.Decision.main(
            InterviewPhase.FUNDAMENTALS, 2));
  }

  @Test
  void followUpsDoNotEndTheInterviewWhenActualTurnsExceedTheMainQuestionCount() {
    assertThat(flow.next(InterviewSize.QUICK, progress(
        InterviewPhase.PROJECT_EXPERIENCE, 2, 1, 1)))
        .isEqualTo(FixedInterviewFlowPolicy.Decision.main(
            InterviewPhase.SCENARIO_TRADEOFF, 1));

    assertThat(flow.next(InterviewSize.QUICK, progress(
        InterviewPhase.SCENARIO_TRADEOFF, 1, 1, 1)))
        .isEqualTo(FixedInterviewFlowPolicy.Decision.end());
  }

  private FixedInterviewFlowPolicy.Progress progress(
      InterviewPhase phase,
      int mainQuestionsAsked,
      int followUpsForCurrentCard,
      int currentCardFollowUpQuota) {
    return new FixedInterviewFlowPolicy.Progress(
        phase, mainQuestionsAsked, followUpsForCurrentCard, currentCardFollowUpQuota);
  }
}
