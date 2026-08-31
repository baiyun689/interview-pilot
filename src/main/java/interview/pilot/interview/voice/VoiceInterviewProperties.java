package interview.pilot.interview.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.voice")
public record VoiceInterviewProperties(
    boolean enabled, Qwen qwen) {
  public VoiceInterviewProperties {
    qwen = qwen == null ? new Qwen("", "qwen3-asr-flash-realtime",
        "wss://dashscope.aliyuncs.com/api-ws/v1/realtime", "qwen-tts-realtime", "Cherry") : qwen;
  }

  public record Qwen(
      String apiKey, String asrModel, String asrUrl, String ttsModel, String voice) {
    public Qwen {
      apiKey = apiKey == null ? "" : apiKey.trim();
      asrModel = required(asrModel, "asrModel");
      asrUrl = required(asrUrl, "asrUrl");
      ttsModel = required(ttsModel, "ttsModel");
      voice = required(voice, "voice");
    }

    private static String required(String value, String name) {
      String normalized = value == null ? "" : value.trim();
      if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
      return normalized;
    }
  }
}
