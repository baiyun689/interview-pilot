package interview.pilot.voice.domain;

/** Kind of stored voice media, defining the immutable storage key layout (plan §9). */
public enum VoiceMediaKind {
  RECORDING("recordings", "source"),
  SPEECH("speech", "audio");

  private final String directory;
  private final String file;

  VoiceMediaKind(String directory, String file) {
    this.directory = directory;
    this.file = file;
  }

  public String directory() {
    return directory;
  }

  public String file() {
    return file;
  }
}
