package interview.pilot.voice.application;

import java.io.Closeable;
import java.io.IOException;

import interview.pilot.voice.domain.VoiceMediaResource;

/**
 * An opened question speech read: the streamable {@link VoiceMediaResource} plus the weak
 * etag derived from the speech row's version and the module's resolved slice bounds.
 *
 * <p>{@code rangeStart}/{@code rangeEnd} are null for full reads; for ranged reads they carry
 * the exact resolved-and-clamped bounds the stream was sliced to, while
 * {@code resource().contentLength()} stays the TOTAL media length. The controller emits
 * Content-Range and Content-Length from these carried values — the range arithmetic lives in
 * exactly one place (the module). Closing the record closes the underlying stream.
 */
public record QuestionSpeechMedia(
    VoiceMediaResource resource, String etag, Long rangeStart, Long rangeEnd)
    implements Closeable {

  @Override
  public void close() throws IOException {
    resource.close();
  }
}
