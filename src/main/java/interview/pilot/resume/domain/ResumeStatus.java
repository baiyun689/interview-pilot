package interview.pilot.resume.domain;

public enum ResumeStatus {
  PENDING,
  ANALYZING,
  READY,
  FAILED;

  public boolean canTransitionTo(ResumeStatus target) {
    return switch (this) {
      case PENDING -> target == ANALYZING;
      case ANALYZING -> target == READY || target == FAILED;
      case FAILED -> target == PENDING;
      case READY -> false;
    };
  }
}
