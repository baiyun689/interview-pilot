package interview.pilot.voice.domain;

/**
 * Stable voice error codes (plan §8.6). Clients react on the code, never on the message.
 * 404 hides cross-user resources, 409 signals state conflicts, 413 signals unusable upload
 * content (too large or too long), 415 signals a deterministic media rejection.
 */
public final class VoiceErrorCodes {

  public static final String VOICE_RECORDING_NOT_FOUND = "VOICE_RECORDING_NOT_FOUND";
  public static final String VOICE_UPLOAD_IN_PROGRESS = "VOICE_UPLOAD_IN_PROGRESS";
  public static final String VOICE_UPLOAD_TOO_LARGE = "VOICE_UPLOAD_TOO_LARGE";
  public static final String VOICE_MEDIA_UNSUPPORTED = "VOICE_MEDIA_UNSUPPORTED";
  public static final String VOICE_DURATION_EXCEEDED = "VOICE_DURATION_EXCEEDED";
  public static final String VOICE_TURN_NOT_CURRENT = "VOICE_TURN_NOT_CURRENT";
  public static final String VOICE_TRANSCRIPTION_IN_PROGRESS = "VOICE_TRANSCRIPTION_IN_PROGRESS";
  public static final String VOICE_TRANSCRIPTION_FAILED = "VOICE_TRANSCRIPTION_FAILED";
  public static final String VOICE_RECORDING_NOT_READY = "VOICE_RECORDING_NOT_READY";
  public static final String VOICE_RECORDING_ALREADY_ATTACHED = "VOICE_RECORDING_ALREADY_ATTACHED";

  /**
   * Diagnostic safe_error stored on FAILED recordings when the media probe failed
   * operationally (ffprobe start/timeout/IO). Not a stable HTTP code: the wire response for
   * operational probe failures stays a generic 500 (plan §14 keeps provider details off the
   * wire); the recording row keeps the code for diagnosis and GET.
   */
  public static final String VOICE_MEDIA_PROBE_FAILED = "VOICE_MEDIA_PROBE_FAILED";

  private VoiceErrorCodes() {}
}
