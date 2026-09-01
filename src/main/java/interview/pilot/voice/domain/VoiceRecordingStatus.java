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
      case UPLOADED -> target == TRANSCRIBING;
      case TRANSCRIBING -> target == READY || target == FAILED;
      case READY -> target == ATTACHED || target == DISCARDED;
      case FAILED -> target == TRANSCRIBING || target == DISCARDED;
      case ATTACHED -> false;
      case DISCARDED -> false;
    };
  }
}
