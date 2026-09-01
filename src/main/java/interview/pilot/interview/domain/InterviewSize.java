package interview.pilot.interview.domain;

import java.util.EnumMap;
import java.util.Map;

public enum InterviewSize {
  QUICK(6, mainQuestions(1, 2, 2, 1)),
  STANDARD(9, mainQuestions(1, 3, 3, 2)),
  DEEP(12, mainQuestions(1, 4, 4, 3));

  private final int totalMainQuestionCount;
  private final Map<InterviewPhase, Integer> mainQuestionCounts;

  InterviewSize(
      int totalMainQuestionCount,
      Map<InterviewPhase, Integer> mainQuestionCounts) {
    this.totalMainQuestionCount = totalMainQuestionCount;
    this.mainQuestionCounts = Map.copyOf(mainQuestionCounts);
  }

  public int totalMainQuestionCount() {
    return totalMainQuestionCount;
  }

  public int mainQuestionCount(InterviewPhase phase) {
    return mainQuestionCounts.getOrDefault(phase, 0);
  }

  public int generatedQuestionCount() {
    return totalMainQuestionCount - mainQuestionCount(InterviewPhase.SELF_INTRODUCTION);
  }

  private static Map<InterviewPhase, Integer> mainQuestions(
      int self, int fundamentals, int project, int scenario) {
    var result = new EnumMap<InterviewPhase, Integer>(InterviewPhase.class);
    result.put(InterviewPhase.SELF_INTRODUCTION, self);
    result.put(InterviewPhase.FUNDAMENTALS, fundamentals);
    result.put(InterviewPhase.PROJECT_EXPERIENCE, project);
    result.put(InterviewPhase.SCENARIO_TRADEOFF, scenario);
    return result;
  }
}
