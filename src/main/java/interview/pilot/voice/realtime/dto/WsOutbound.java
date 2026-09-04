package interview.pilot.voice.realtime.dto;

/**
 * Outbound server messages. Every payload carries a {@code type} discriminator consumed by the
 * browser WebSocket client:
 *
 * <ul>
 *   <li>{@code control} — lifecycle (welcome / turn / idle / ended);</li>
 *   <li>{@code subtitle} — live ASR text ({@code isFinal=false} partial, {@code true} committed);</li>
 *   <li>{@code text} — interviewer question text (sent before its audio for low latency);</li>
 *   <li>{@code audio} — interviewer speech as a base64 WAV data URL payload plus its text;</li>
 *   <li>{@code error} — a sanitized, user-presentable error.</li>
 * </ul>
 */
public final class WsOutbound {

  private WsOutbound() {
  }

  public record Control(String type, String action, String message, Long timestamp,
                        Integer turnNo, String status) {
    public static Control of(String action, String message) {
      return new Control("control", action, message, System.currentTimeMillis(), null, null);
    }
  }

  public record Subtitle(String type, String text, boolean isFinal) {
    public static Subtitle partial(String text) {
      return new Subtitle("subtitle", text, false);
    }

    public static Subtitle finalized(String text) {
      return new Subtitle("subtitle", text, true);
    }
  }

  public record Text(String type, String content, int turnNo) {
    public static Text of(String content, int turnNo) {
      return new Text("text", content, turnNo);
    }
  }

  public record Audio(String type, String data, String text, int turnNo) {
    public static Audio of(String base64Wav, String text, int turnNo) {
      return new Audio("audio", base64Wav, text, turnNo);
    }
  }

  public record Error(String type, String code, String message) {
    public static Error of(String code, String message) {
      return new Error("error", code, message);
    }
  }
}
