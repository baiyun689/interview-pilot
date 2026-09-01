package interview.pilot.voice.domain;

import java.util.List;

/** Media types the voice upload path accepts in the first release (plan §2.4). */
public final class VoiceMimeTypes {

  public static final List<String> SUPPORTED = List.of(
      "audio/webm", "audio/ogg", "audio/mp4", "audio/wav", "audio/mpeg");

  private VoiceMimeTypes() {}
}
