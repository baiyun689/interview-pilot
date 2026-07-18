package interview.pilot.interview.domain;

public enum TurnStatus {
  ASKED,
  PROCESSING,
  COMPLETED,
  FAILED;

  public boolean canTransitionTo(TurnStatus target) {
    return switch (this) {
      case ASKED -> target == PROCESSING;
      case PROCESSING -> target == COMPLETED || target == FAILED;
      case FAILED -> target == PROCESSING;
      case COMPLETED -> false;
    };
  }
}
