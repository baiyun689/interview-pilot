package interview.pilot.interview.domain;

import java.util.List;

import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.interview.skill.GroundingUse;

public record InterviewPlanItem(
    String stageId,
    String competencyId,
    String competency,
    PlanPriority priority,
    int turnBudget,
    List<String> evidenceTargets,
    List<InterviewQuestionMode> questionModes,
    String rationale,
    boolean ragEnabled,
    List<String> followUpAxes,
    int followUpLimit,
    String resumeEntryPoint,
    SkillRetrievalPolicy retrievalPolicy) {

  public InterviewPlanItem {
    stageId = required(stageId, "stageId", 64);
    competencyId = required(competencyId, "competencyId", 64);
    competency = required(competency, "competency", 100);
    priority = priority == null ? PlanPriority.SKILL_BASELINE : priority;
    if (turnBudget < 0 || turnBudget > 15) {
      throw new IllegalArgumentException("turnBudget must be between 0 and 15");
    }
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    if (evidenceTargets.isEmpty()) {
      throw new IllegalArgumentException("evidenceTargets must not be empty");
    }
    questionModes = questionModes == null || questionModes.isEmpty()
        ? List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM)
        : List.copyOf(questionModes);
    rationale = rationale == null ? "" : rationale.trim();
    followUpAxes = followUpAxes == null ? List.of() : List.copyOf(followUpAxes);
    if (followUpLimit < 0 || followUpLimit > 5) {
      throw new IllegalArgumentException("followUpLimit must be between 0 and 5");
    }
    resumeEntryPoint = resumeEntryPoint == null ? "" : resumeEntryPoint.trim();
    retrievalPolicy = retrievalPolicy == null
        ? (ragEnabled ? new SkillRetrievalPolicy(true, List.of(),
            List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT))
            : SkillRetrievalPolicy.disabled())
        : retrievalPolicy;
  }

  public InterviewPlanItem(
      String stageId, String competencyId, String competency, PlanPriority priority,
      int turnBudget, List<String> evidenceTargets, List<InterviewQuestionMode> questionModes,
      String rationale, boolean ragEnabled, List<String> followUpAxes, int followUpLimit,
      String resumeEntryPoint) {
    this(stageId, competencyId, competency, priority, turnBudget, evidenceTargets,
        questionModes, rationale, ragEnabled, followUpAxes, followUpLimit, resumeEntryPoint, null);
  }

  public InterviewPlanItem(
      String stageId, String competencyId, String competency, PlanPriority priority,
      int turnBudget, List<String> evidenceTargets, List<InterviewQuestionMode> questionModes,
      String rationale, boolean ragEnabled, List<String> followUpAxes, int followUpLimit) {
    this(stageId, competencyId, competency, priority, turnBudget, evidenceTargets,
        questionModes, rationale, ragEnabled, followUpAxes, followUpLimit, "", null);
  }

  public InterviewPlanItem(
      String stageId, String competencyId, String competency, PlanPriority priority,
      int turnBudget, List<String> evidenceTargets, List<InterviewQuestionMode> questionModes,
      String rationale, boolean ragEnabled) {
    this(stageId, competencyId, competency, priority, turnBudget, evidenceTargets,
        questionModes, rationale, ragEnabled, List.of(),
        Math.min(2, Math.max(0, turnBudget - 1)), "", null);
  }

  public static InterviewPlanItem legacy(String competency, int turnBudget, int index) {
    return new InterviewPlanItem(
        "technical_depth", "legacy-" + (index + 1), competency,
        PlanPriority.REQUIRED, turnBudget,
        List.of("实际经验", "机制理解", "边界与取舍"),
        List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM,
            InterviewQuestionMode.FAILURE),
        "历史能力计划兼容项", false,
        List.of("experience", "mechanism", "failure"),
        Math.min(2, Math.max(0, turnBudget - 1)), "", SkillRetrievalPolicy.disabled());
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
