package interview.pilot.voice.domain;

public enum VoiceRecordingStatus {
  RECEIVING,
  UPLOADED,
  TRANSCRIBING,
  READY,

  /**
   * Bound to the submitted answer attempt (§6.1): the binding happens inside the claim
   * transaction and is irreversible. A FAILED attempt also keeps its recording ATTACHED —
   * a failed attempt must be resubmitted with a new requestId, so the client should
   * re-record (or fall back to text) rather than rebind the recording.
   */
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
