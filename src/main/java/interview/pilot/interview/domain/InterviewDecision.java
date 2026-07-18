package interview.pilot.interview.domain;

public record InterviewDecision(
    NextStep nextStep,
    DifficultyAdjustment difficultyAdjustment,
    String targetCompetency,
    String probeFocus,
    String reason,
    double confidence) {

  public InterviewDecision {
    targetCompetency = normalize(targetCompetency);
    probeFocus = normalize(probeFocus);
    reason = normalize(reason);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
