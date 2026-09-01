package interview.pilot.voice.application;

import java.io.Closeable;
import java.io.IOException;

import interview.pilot.voice.domain.VoiceMediaResource;

/**
 * An opened question speech read: the streamable {@link VoiceMediaResource} (for ranged
 * requests already seeked and bounded to the slice, with {@code contentLength} still the
 * TOTAL media length so the controller can emit Content-Range) plus the weak etag derived
 * from the speech row's version. Closing the record closes the underlying stream.
 */
public record QuestionSpeechMedia(VoiceMediaResource resource, String etag)
    implements Closeable {

  @Override
  public void close() throws IOException {
    resource.close();
  }
}
