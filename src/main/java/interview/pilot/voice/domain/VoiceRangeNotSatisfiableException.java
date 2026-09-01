package interview.pilot.voice.domain;

/**
 * The requested byte range starts at or beyond the media length (RFC 7233 §2.1). Carries the
 * total length so the media controller can emit the mandatory
 * {@code Content-Range: bytes * /} followed by the total length on the 416 response. This is
 * an HTTP-level media condition, not a stable business code (plan §8.6) — it is handled by
 * the controller, never by the global advice.
 */
public class VoiceRangeNotSatisfiableException extends RuntimeException {

  private final long contentLength;

  public VoiceRangeNotSatisfiableException(long contentLength) {
    super("The requested byte range is not satisfiable");
    this.contentLength = contentLength;
  }

  public long contentLength() {
    return contentLength;
  }
}
