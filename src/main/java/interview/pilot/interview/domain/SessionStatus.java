package interview.pilot.interview.domain;

public enum SessionStatus {
  CREATED,
  INTERVIEWING,
  EVALUATING,
  COMPLETED,
  FAILED;

  public boolean canTransitionTo(SessionStatus target) {
    return switch (this) {
      case CREATED -> target == INTERVIEWING || target == FAILED;
      case INTERVIEWING -> target == EVALUATING || target == FAILED;
      case EVALUATING -> target == COMPLETED || target == FAILED;
      case COMPLETED, FAILED -> false;
    };
  }
}
