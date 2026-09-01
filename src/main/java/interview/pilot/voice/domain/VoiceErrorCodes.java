package interview.pilot.voice.domain;

/**
 * Stable voice error codes (plan §8.6). Task 4 wires the deterministic rejections to HTTP
 * 413 (VOICE_UPLOAD_TOO_LARGE) and 415 (VOICE_MEDIA_UNSUPPORTED); clients react on the code,
 * never on the message.
 */
public final class VoiceErrorCodes {

  public static final String VOICE_MEDIA_UNSUPPORTED = "VOICE_MEDIA_UNSUPPORTED";
  public static final String VOICE_UPLOAD_TOO_LARGE = "VOICE_UPLOAD_TOO_LARGE";

  private VoiceErrorCodes() {}
}
