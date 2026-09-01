package interview.pilot.voice.domain;

/**
 * Deterministic rejection: the media stream exceeds the configured upload limit while it is
 * being copied. Maps to VOICE_UPLOAD_TOO_LARGE / HTTP 413 in the upload module.
 */
public class VoiceMediaTooLargeException extends RuntimeException {

  public VoiceMediaTooLargeException(long maxBytes) {
    super("Voice media exceeds the " + maxBytes + " byte upload limit");
  }
}
