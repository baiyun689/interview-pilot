package interview.pilot.voice.domain;

public enum QuestionSpeechStatus {
  PENDING,
  SYNTHESIZING,
  READY,
  FAILED;

  public boolean canTransitionTo(QuestionSpeechStatus target) {
    return switch (this) {
      // PENDING → FAILED is the markDead path when retries die before the claim transaction
      // ever ran: the message is dead-lettered but the row is still PENDING (plan §11 —
      // mirror of the recording's pre-approved UPLOADED → FAILED transition).
      case PENDING -> target == SYNTHESIZING || target == FAILED;
      case SYNTHESIZING -> target == READY || target == FAILED;
      case READY -> false;
      case FAILED -> target == PENDING;
    };
  }
}
