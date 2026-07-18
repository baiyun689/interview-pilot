package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;

public final class InterviewDecisionContextFactory {
  public DecisionContext create(
      Difficulty difficulty,
      String currentCompetency,
      List<String> requiredCompetencies,
      int currentTurn,
      int totalTurnBudget,
      double minimumConfidence,
      List<CompletedTurnEvidence> completedTurns,
      AnswerEvaluation currentEvaluation) {
    List<String> covered = new ArrayList<>();
    for (CompletedTurnEvidence turn : completedTurns) {
      if (turn.score() >= 60 && !turn.evidence().isEmpty()) {
        covered.add(turn.competency());
      }
    }
    if (currentEvaluation.score() >= 60 && !currentEvaluation.evidence().isEmpty()) {
      covered.add(currentCompetency);
    }
    int followUpCount = 0;
    for (int index = completedTurns.size() - 1; index >= 0; index--) {
      if (!key(completedTurns.get(index).competency()).equals(key(currentCompetency))) break;
      followUpCount++;
    }
    return new DecisionContext(
        difficulty, currentCompetency, covered, requiredCompetencies, followUpCount,
        currentTurn, totalTurnBudget, minimumConfidence);
  }

  private String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public record CompletedTurnEvidence(String competency, double score, List<String> evidence) {
    public CompletedTurnEvidence {
      competency = competency == null ? "" : competency.trim();
      evidence = List.copyOf(evidence);
    }
  }
}
