package interview.pilot.voice.application;

import java.util.Objects;

/**
 * Voice used for a synthesis call, derived from the TTS configuration
 * ({@code app.voice.tts.*}): provider/model/voice are captured on the question_speech row at
 * creation, so a later config change never re-synthesizes an old question with a new voice.
 */
public record VoiceProfile(String provider, String model, String voice) {
  public VoiceProfile {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(voice, "voice");
  }
}
