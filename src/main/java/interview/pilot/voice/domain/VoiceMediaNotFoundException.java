package interview.pilot.voice.domain;

/**
 * The storage key is well-formed and passed every security check (symlink, hard link,
 * private permissions), but no media file exists at it. Cleanup (plan §14, Task 11) treats
 * this as "file already gone" and must never conflate it with security rejections, which
 * surface as {@link IllegalArgumentException} with distinct messages.
 */
public class VoiceMediaNotFoundException extends RuntimeException {

  public VoiceMediaNotFoundException() {
    super("Voice media does not exist");
  }
}
