package interview.pilot.voice.realtime.dto;

/**
 * Inbound browser message over the voice WebSocket.
 *
 * <ul>
 *   <li>{@code {"type":"audio","data":"<base64 pcm>"}} — one microphone chunk;</li>
 *   <li>{@code {"type":"control","action":"submit"|"end_interview"|"mute"|"unmute","text":"..."}}.</li>
 * </ul>
 * Unknown fields are ignored (the shared ObjectMapper leaves FAIL_ON_UNKNOWN_PROPERTIES off).
 */
public record WsInbound(String type, String data, String action, String text) {

  public boolean isAudio() {
    return "audio".equals(type);
  }

  public boolean isControl() {
    return "control".equals(type);
  }

  public String normalizedAction() {
    return action == null ? "" : action.trim().toLowerCase();
  }
}
