package interview.pilot.voice.domain;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Opened voice media: the content stream plus its length and, when the caller knows it, the
 * media type (the store does not persist probe metadata; readers combine it with database
 * metadata).
 *
 * <p>{@code path} is the validated file location when the resource is backed by the file
 * system and is how later ranged reads (Task 8) will seek over the file; in-memory resources
 * carry {@code null}. The path is never handed to a web response directly — authorized media
 * endpoints only.
 */
public record VoiceMediaResource(
    InputStream inputStream, long contentLength, String mediaType, Path path)
    implements Closeable {

  public VoiceMediaResource(InputStream inputStream, long contentLength, String mediaType) {
    this(inputStream, contentLength, mediaType, null);
  }

  @Override
  public void close() throws IOException {
    if (inputStream != null) {
      inputStream.close();
    }
  }
}
