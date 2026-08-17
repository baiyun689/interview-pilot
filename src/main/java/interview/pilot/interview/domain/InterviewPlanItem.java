package interview.pilot.interview.domain;

import java.util.List;

import interview.pilot.interview.skill.InterviewQuestionMode;

public record InterviewPlanItem(
    String stageId,
    String competencyId,
    String competency,
    PlanPriority priority,
    int turnBudget,
    List<String> evidenceTargets,
    List<InterviewQuestionMode> questionModes,
    String rationale,
    boolean ragEnabled) {

  public InterviewPlanItem {
    stageId = required(stageId, "stageId", 64);
    competencyId = required(competencyId, "competencyId", 64);
    competency = required(competency, "competency", 100);
    priority = priority == null ? PlanPriority.SKILL_BASELINE : priority;
    if (turnBudget < 0 || turnBudget > 15) {
      throw new IllegalArgumentException("turnBudget must be between 0 and 15");
    }
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    questionModes = questionModes == null || questionModes.isEmpty()
        ? List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM)
        : List.copyOf(questionModes);
    rationale = rationale == null ? "" : rationale.trim();
  }

  public static InterviewPlanItem legacy(String competency, int turnBudget, int index) {
    return new InterviewPlanItem(
        "technical_depth", "legacy-" + (index + 1), competency,
        PlanPriority.REQUIRED, turnBudget,
        List.of("实际经验", "机制理解", "边界与取舍"),
        List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM,
            InterviewQuestionMode.FAILURE),
        "历史能力计划兼容项", false);
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
