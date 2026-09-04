package interview.pilot.voice.realtime.asr;

import java.util.function.Consumer;

/**
 * Streaming automatic-speech-recognition client. One upstream recognition session per interview
 * session id. The implementation runs server-side VAD: {@code onPartial} drives live subtitles while
 * {@code onFinal} delivers a finished utterance segment.
 */
public interface StreamingAsrClient {

  /** Open an upstream recognition session and register its result/error callbacks. */
  void start(String sessionId,
             Consumer<String> onFinal,
             Consumer<String> onPartial,
             Consumer<Throwable> onError);

  /** Tear down and re-open the upstream session (recovery after a server-side close). */
  void restart(String sessionId,
               Consumer<String> onFinal,
               Consumer<String> onPartial,
               Consumer<Throwable> onError);

  /** Append one chunk of raw 16kHz mono s16le PCM. */
  void sendAudio(String sessionId, byte[] pcm);

  /** Whether an upstream session is currently registered. */
  boolean isActive(String sessionId);

  /** Close the upstream session and release its resources. */
  void stop(String sessionId);
}
