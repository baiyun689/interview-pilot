package interview.pilot.interview.domain;

public enum SessionStatus {
  PREPARING,
  READY,
  INTERVIEWING,
  EVALUATING,
  COMPLETED,
  PREPARATION_FAILED,
  EVALUATION_FAILED,
  CANCELLED;

  public boolean canTransitionTo(SessionStatus target) {
    return switch (this) {
      case PREPARING -> target == READY || target == PREPARATION_FAILED;
      case READY -> target == INTERVIEWING;
      case INTERVIEWING -> target == EVALUATING;
      case EVALUATING -> target == COMPLETED || target == EVALUATION_FAILED;
      case PREPARATION_FAILED -> target == PREPARING;
      case EVALUATION_FAILED -> target == EVALUATING;
      case COMPLETED, CANCELLED -> false;
    };
  }
}
