package interview.pilot.voice.domain;

/**
 * Operational probe failure: ffprobe could not be started, timed out, was interrupted or its
 * output could not be read. Unlike {@link VoiceMediaUnsupportedException} this is not a
 * deterministic media rejection and is safe to retry.
 */
public class VoiceMediaProbeException extends RuntimeException {

  public VoiceMediaProbeException(String message, Throwable cause) {
    super(message, cause);
  }
}
