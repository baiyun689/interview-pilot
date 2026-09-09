package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.*;
import static interview.pilot.interview.domain.InterviewPhase.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewExecutionPlanTest {
  @Test void followsFrozenOrderWithoutAddingMissingPhasesOrChargingFollowUpsAsMainQuestions() {
    var project = new InterviewExecutionPlan.Card(41, PROJECT_EXPERIENCE, 2);
    var fundamentals = new InterviewExecutionPlan.Card(7, FUNDAMENTALS, 0);
    var plan = new InterviewExecutionPlan(List.of(project, fundamentals));
    assertThat(plan.first()).isEqualTo(project);
    assertThat(plan.next(41, 0)).isEqualTo(new InterviewExecutionPlan.Step(project, true));
    assertThat(plan.next(41, 1)).isEqualTo(new InterviewExecutionPlan.Step(project, true));
    assertThat(plan.next(41, 2)).isEqualTo(new InterviewExecutionPlan.Step(fundamentals, false));
    assertThat(plan.next(7, 0).finished()).isTrue();
  }
  @Test void rejectsUnknownCardDuplicateCardAndImpossibleFollowUpProgress() {
    var card = new InterviewExecutionPlan.Card(1, SELF_INTRODUCTION, 0);
    var plan = new InterviewExecutionPlan(List.of(card));
    assertThatThrownBy(() -> plan.next(2, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plan.next(1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewExecutionPlan(List.of(card, card))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewExecutionPlan.Card(2, SELF_INTRODUCTION, 1)).isInstanceOf(IllegalArgumentException.class);
  }
}
