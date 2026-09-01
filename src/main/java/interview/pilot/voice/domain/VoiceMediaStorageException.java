package interview.pilot.voice.domain;

/** I/O failure while storing, opening or deleting voice media files. */
public class VoiceMediaStorageException extends RuntimeException {

  public VoiceMediaStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
