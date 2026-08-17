package interview.pilot.interview.strategy;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;

public record TurnDirective(
    String stageId,
    String competency,
    Difficulty difficulty,
    List<String> evidenceTargets,
    InterviewQuestionMode questionMode,
    boolean ragEnabled,
    String reason) {

  public TurnDirective {
    stageId = required(stageId, "stageId", 64);
    competency = required(competency, "competency", 100);
    if (difficulty == null) throw new IllegalArgumentException("difficulty is required");
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    questionMode = questionMode == null ? InterviewQuestionMode.PROJECT : questionMode;
    reason = reason == null ? "" : reason.trim();
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
