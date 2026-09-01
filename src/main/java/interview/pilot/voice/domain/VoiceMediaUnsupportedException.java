package interview.pilot.voice.domain;

/**
 * Deterministic rejection: the media container, stream or duration could not be verified
 * against the audio whitelist (plan §2.4). Maps to VOICE_MEDIA_UNSUPPORTED / HTTP 415 in the
 * upload module.
 */
public class VoiceMediaUnsupportedException extends RuntimeException {

  public VoiceMediaUnsupportedException() {
    super("Voice media format is not supported");
  }
}
