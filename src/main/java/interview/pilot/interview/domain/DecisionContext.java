package interview.pilot.interview.domain;

import java.util.Objects;

/** 决策所需的本轮上下文；证据进度由 InterviewProgress 提供，不再在此重复表达。 */
public record DecisionContext(
    Difficulty currentDifficulty,
    String currentCompetency,
    int currentTurn,
    int totalTurnBudget,
    double minimumConfidence) {

  public DecisionContext {
    currentDifficulty = Objects.requireNonNull(currentDifficulty, "currentDifficulty must not be null");
    currentCompetency = currentCompetency == null ? "" : currentCompetency.trim();
    if (currentTurn < 0) {
      throw new IllegalArgumentException("currentTurn must not be negative");
    }
    if (totalTurnBudget < 0) {
      throw new IllegalArgumentException("totalTurnBudget must not be negative");
    }
    if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
      throw new IllegalArgumentException("minimumConfidence must be finite and between 0 and 1");
    }
  }
}
