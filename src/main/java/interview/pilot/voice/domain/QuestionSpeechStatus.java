package interview.pilot.voice.domain;

public enum QuestionSpeechStatus {
  PENDING,
  SYNTHESIZING,
  READY,
  FAILED;

  public boolean canTransitionTo(QuestionSpeechStatus target) {
    return switch (this) {
      case PENDING -> target == SYNTHESIZING;
      case SYNTHESIZING -> target == READY || target == FAILED;
      case READY -> false;
      case FAILED -> target == PENDING;
    };
  }
}
