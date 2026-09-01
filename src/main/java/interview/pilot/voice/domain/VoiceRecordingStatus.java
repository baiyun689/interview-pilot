package interview.pilot.voice.domain;

public enum VoiceRecordingStatus {
  RECEIVING,
  UPLOADED,
  TRANSCRIBING,
  READY,
  ATTACHED,
  FAILED,
  DISCARDED;

  public boolean canTransitionTo(VoiceRecordingStatus target) {
    return switch (this) {
      case RECEIVING -> target == UPLOADED || target == FAILED || target == DISCARDED;
      // UPLOADED → FAILED is the markDead path when retries die before the claim transaction
      // ever ran: the message is dead-lettered but the row is still UPLOADED (plan §10 step 8).
      case UPLOADED -> target == TRANSCRIBING || target == FAILED;
      case TRANSCRIBING -> target == READY || target == FAILED;
      case READY -> target == ATTACHED || target == DISCARDED;
      case FAILED -> target == TRANSCRIBING || target == DISCARDED;
      case ATTACHED -> false;
      case DISCARDED -> false;
    };
  }
}
