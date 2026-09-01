package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FixedInterviewEvidencePolicyTest {
  private final FixedInterviewEvidencePolicy policy = new FixedInterviewEvidencePolicy();

  @Test
  void acceptsOneMainAndThePersistedFollowUpQuotaForEveryCard() {
    var cards = List.of(
        card(1, InterviewPhase.SELF_INTRODUCTION, 0),
        card(2, InterviewPhase.FUNDAMENTALS, 2));
    var turns = List.of(
        turn(1, 1, InterviewPhase.SELF_INTRODUCTION, QuestionType.SELF_INTRODUCTION),
        turn(2, 2, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN),
        turn(3, 2, InterviewPhase.FUNDAMENTALS, QuestionType.FOLLOW_UP),
        turn(4, 2, InterviewPhase.FUNDAMENTALS, QuestionType.FOLLOW_UP));

    assertThatCode(() -> policy.requireComplete(2, cards, turns)).doesNotThrowAnyException();
  }

  @Test
  void rejectsAMissingMainEvenWhenAnExtraFollowUpKeepsTheTotalCountEqual() {
    var cards = List.of(
        card(1, InterviewPhase.SELF_INTRODUCTION, 0),
        card(2, InterviewPhase.FUNDAMENTALS, 1),
        card(3, InterviewPhase.PROJECT_EXPERIENCE, 1));
    var turns = List.of(
        turn(1, 1, InterviewPhase.SELF_INTRODUCTION, QuestionType.SELF_INTRODUCTION),
        turn(2, 2, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN),
        turn(3, 2, InterviewPhase.FUNDAMENTALS, QuestionType.FOLLOW_UP),
        turn(4, 2, InterviewPhase.FUNDAMENTALS, QuestionType.FOLLOW_UP),
        turn(5, 3, InterviewPhase.PROJECT_EXPERIENCE, QuestionType.FOLLOW_UP));

    assertThatThrownBy(() -> policy.requireComplete(3, cards, turns))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incomplete");
  }

  private FixedInterviewEvidencePolicy.CardEvidence card(
      long id, InterviewPhase phase, int quota) {
    return new FixedInterviewEvidencePolicy.CardEvidence(id, phase, quota);
  }

  private FixedInterviewEvidencePolicy.TurnEvidence turn(
      int turnNo, long cardId, InterviewPhase phase, QuestionType type) {
    return new FixedInterviewEvidencePolicy.TurnEvidence(
        turnNo, cardId, phase, type, TurnStatus.COMPLETED);
  }
}
