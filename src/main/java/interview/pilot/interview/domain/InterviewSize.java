package interview.pilot.interview.domain;

import java.util.EnumMap;
import java.util.Map;

public enum InterviewSize {
  QUICK(6, budgets(1, 2, 2, 1), minimums(2, 1, 1)),
  STANDARD(9, budgets(1, 3, 3, 2), minimums(3, 2, 1)),
  DEEP(12, budgets(1, 4, 4, 3), minimums(4, 2, 2));

  private final int totalTurns;
  private final Map<InterviewPhase, Integer> turnBudgets;
  private final Map<InterviewPhase, Integer> minimumMainQuestions;

  InterviewSize(
      int totalTurns,
      Map<InterviewPhase, Integer> turnBudgets,
      Map<InterviewPhase, Integer> minimumMainQuestions) {
    this.totalTurns = totalTurns;
    this.turnBudgets = Map.copyOf(turnBudgets);
    this.minimumMainQuestions = Map.copyOf(minimumMainQuestions);
  }

  public int totalTurns() {
    return totalTurns;
  }

  public int turnBudget(InterviewPhase phase) {
    return turnBudgets.getOrDefault(phase, 0);
  }

  public int minimumMainQuestions(InterviewPhase phase) {
    return minimumMainQuestions.getOrDefault(phase, 0);
  }

  public int generatedQuestionCount() {
    return totalTurns - turnBudget(InterviewPhase.SELF_INTRODUCTION);
  }

  public boolean canAskFollowUp(
      InterviewPhase phase, int mainQuestionsAsked, int phaseTurnsUsed) {
    if (phase == null || !phase.allowsFollowUp()) return false;
    int remainingTurns = turnBudget(phase) - phaseTurnsUsed;
    int remainingRequiredMain = Math.max(
        0, minimumMainQuestions(phase) - mainQuestionsAsked);
    return remainingTurns > remainingRequiredMain;
  }

  private static Map<InterviewPhase, Integer> budgets(
      int self, int fundamentals, int project, int scenario) {
    var result = new EnumMap<InterviewPhase, Integer>(InterviewPhase.class);
    result.put(InterviewPhase.SELF_INTRODUCTION, self);
    result.put(InterviewPhase.FUNDAMENTALS, fundamentals);
    result.put(InterviewPhase.PROJECT_EXPERIENCE, project);
    result.put(InterviewPhase.SCENARIO_TRADEOFF, scenario);
    return result;
  }

  private static Map<InterviewPhase, Integer> minimums(
      int fundamentals, int project, int scenario) {
    var result = new EnumMap<InterviewPhase, Integer>(InterviewPhase.class);
    result.put(InterviewPhase.SELF_INTRODUCTION, 1);
    result.put(InterviewPhase.FUNDAMENTALS, fundamentals);
    result.put(InterviewPhase.PROJECT_EXPERIENCE, project);
    result.put(InterviewPhase.SCENARIO_TRADEOFF, scenario);
    return result;
  }
}
