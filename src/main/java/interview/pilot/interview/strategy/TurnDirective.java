package interview.pilot.interview.strategy;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;

public record TurnDirective(
    String stageId,
    String competency,
    Difficulty difficulty,
    List<String> evidenceTargets,
    InterviewQuestionMode questionMode,
    boolean ragEnabled,
    String probeFocus,
    String reason,
    String resumeEntryPoint,
    SkillRetrievalPolicy retrievalPolicy) {

  public TurnDirective {
    stageId = required(stageId, "stageId", 64);
    competency = required(competency, "competency", 100);
    if (difficulty == null) throw new IllegalArgumentException("difficulty is required");
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    questionMode = questionMode == null ? InterviewQuestionMode.PROJECT : questionMode;
    probeFocus = probeFocus == null ? "" : probeFocus.trim();
    reason = reason == null ? "" : reason.trim();
    resumeEntryPoint = resumeEntryPoint == null ? "" : resumeEntryPoint.trim();
    retrievalPolicy = retrievalPolicy == null
        ? (ragEnabled ? new SkillRetrievalPolicy(true, List.of(), List.of(),
            List.of("question_generation", "fact_verification"))
            : SkillRetrievalPolicy.disabled())
        : retrievalPolicy;
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason,
      String resumeEntryPoint) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        probeFocus, reason, resumeEntryPoint, null);
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        probeFocus, reason, "", null);
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String reason) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        "", reason, "", null);
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
