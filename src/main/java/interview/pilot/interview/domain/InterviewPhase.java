package interview.pilot.interview.domain;

public enum InterviewPhase {
  SELF_INTRODUCTION,
  FUNDAMENTALS,
  PROJECT_EXPERIENCE,
  SCENARIO_TRADEOFF;

  public boolean allowsFollowUp() {
    return this != SELF_INTRODUCTION;
  }
}
