package interview.pilot.interview.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates that final-report evidence exactly matches the persisted question deck. */
public final class FixedInterviewEvidencePolicy {
  public void requireComplete(
      int totalMainQuestionCount,
      List<CardEvidence> cards,
      List<TurnEvidence> turns) {
    if (cards == null || turns == null || cards.size() != totalMainQuestionCount) {
      throw incomplete();
    }
    Set<Long> cardIds = new HashSet<>();
    for (CardEvidence card : cards) {
      if (card == null || card.phase() == null || !cardIds.add(card.cardId())) {
        throw incomplete();
      }
    }
    for (int index = 0; index < turns.size(); index++) {
      TurnEvidence turn = turns.get(index);
      if (turn == null || turn.turnNo() != index + 1 || turn.status() != TurnStatus.COMPLETED
          || !cardIds.contains(turn.sourceCardId())) {
        throw incomplete();
      }
      CardEvidence card = cards.stream()
          .filter(candidate -> candidate.cardId() == turn.sourceCardId())
          .findFirst().orElseThrow(this::incomplete);
      if (turn.phase() != card.phase()) throw incomplete();
    }
    for (CardEvidence card : cards) {
      QuestionType mainType = card.phase() == InterviewPhase.SELF_INTRODUCTION
          ? QuestionType.SELF_INTRODUCTION : QuestionType.MAIN;
      long mainCount = turns.stream().filter(turn ->
          turn.sourceCardId() == card.cardId() && turn.questionType() == mainType).count();
      long followUpCount = turns.stream().filter(turn ->
          turn.sourceCardId() == card.cardId()
              && turn.questionType() == QuestionType.FOLLOW_UP).count();
      long unexpectedCount = turns.stream().filter(turn ->
          turn.sourceCardId() == card.cardId()
              && turn.questionType() != mainType
              && turn.questionType() != QuestionType.FOLLOW_UP).count();
      if (mainCount != 1 || followUpCount != card.followUpQuota() || unexpectedCount != 0) {
        throw incomplete();
      }
    }
  }

  private IllegalStateException incomplete() {
    return new IllegalStateException("Completed interview evidence is incomplete");
  }

  public record CardEvidence(long cardId, InterviewPhase phase, int followUpQuota) { }

  public record TurnEvidence(
      int turnNo,
      long sourceCardId,
      InterviewPhase phase,
      QuestionType questionType,
      TurnStatus status) { }
}
