package interview.pilot.voice.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.voice.application.QuestionSpeechMedia;
import interview.pilot.voice.application.QuestionSpeechModule;
import interview.pilot.voice.application.QuestionSpeechView;
import interview.pilot.voice.application.QuestionSpeechViewStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceRangeNotSatisfiableException;

/**
 * Question speech endpoints (plan §8.5). The module maps every rejection to a stable
 * BusinessException code handled by the global advice; the media endpoint is the only one
 * that answers HTTP range semantics itself (206/416 with the mandatory Content-Range headers),
 * streaming the module's validated resource — ownership is re-validated per request and the
 * body is never buffered by the controller (the module hands out a bounded stream).
 *
 * <p>Cache design: audio is private user data served from an immutable storage key — the
 * browser may keep it for one hour (Cache-Control: private, max-age=3600), shared caches must
 * not, and the weak ETag over the speech row's version changes on any re-synthesis. The retry
 * URL carries the turn (like the view URL); the controller resolves the speech id through the
 * view so a turn without speech is a stable 409 QUESTION_SPEECH_NOT_READY, never a 404.
 */
@RestController
@RequestMapping("/api/interviews")
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class QuestionSpeechController {

  private static final String CACHE_CONTROL = "private, max-age=3600";
  private static final String CONTENT_DISPOSITION_INLINE = "inline";
  private static final String ACCEPT_RANGES_BYTES = "bytes";

  private final QuestionSpeechModule module;
  private final CurrentUserProvider currentUser;

  public QuestionSpeechController(QuestionSpeechModule module, CurrentUserProvider currentUser) {
    this.module = module;
    this.currentUser = currentUser;
  }

  @GetMapping("/{sessionId}/turns/{turnNo}/speech")
  public ResponseEntity<QuestionSpeechView> speech(
      @PathVariable UUID sessionId, @PathVariable int turnNo) {
    QuestionSpeechView view = module.getOrSchedule(currentUser.require(), sessionId, turnNo);
    boolean inProgress = view.status() == QuestionSpeechViewStatus.PENDING
        || view.status() == QuestionSpeechViewStatus.SYNTHESIZING;
    // Plan §8.5: PENDING/SYNTHESIZING answer 202 (the client polls again), every other
    // status — including the view-only NOT_AVAILABLE — answers 200 with the view body.
    return ResponseEntity.status(inProgress ? HttpStatus.ACCEPTED : HttpStatus.OK).body(view);
  }

  @PostMapping("/{sessionId}/turns/{turnNo}/speech/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void retry(@PathVariable UUID sessionId, @PathVariable int turnNo) {
    CurrentUser user = currentUser.require();
    QuestionSpeechView view = module.getOrSchedule(user, sessionId, turnNo);
    if (view.speechId() == null) {
      // A turn without speech (TEXT session or TTS unconfigured) has nothing to retry; the
      // recording module's retry semantics answer state conflicts as 409 as well.
      throw new BusinessException(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY,
          "The question speech is not retryable", HttpStatus.CONFLICT);
    }
    module.retry(user, sessionId, view.speechId());
  }

  @GetMapping("/{sessionId}/speech/{speechId}/media")
  public ResponseEntity<InputStreamResource> media(
      @PathVariable UUID sessionId, @PathVariable UUID speechId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
    CurrentUser user = currentUser.require();
    if (!hasRange(rangeHeader)) {
      return full(user, sessionId, speechId);
    }
    List<HttpRange> ranges;
    try {
      ranges = HttpRange.parseRanges(rangeHeader);
    } catch (IllegalArgumentException malformed) {
      return rangeNotSatisfiable(user, sessionId, speechId);
    }
    if (ranges.size() != 1) {
      // Multipart/byteranges is out of scope (plan §8.5: single byte ranges) and RFC 7233
      // lets a server ignore Range forms it does not support: serve the full body.
      return full(user, sessionId, speechId);
    }
    return partial(user, sessionId, speechId, ranges.getFirst());
  }

  private ResponseEntity<InputStreamResource> full(
      CurrentUser user, UUID sessionId, UUID speechId) {
    QuestionSpeechMedia media = module.open(user, sessionId, speechId, null);
    return ResponseEntity.status(HttpStatus.OK)
        .headers(mediaHeaders(media))
        .header(HttpHeaders.CONTENT_LENGTH, Long.toString(media.resource().contentLength()))
        .body(new InputStreamResource(media.resource().inputStream()));
  }

  private ResponseEntity<InputStreamResource> partial(
      CurrentUser user, UUID sessionId, UUID speechId, HttpRange range) {
    QuestionSpeechMedia media;
    try {
      media = module.open(user, sessionId, speechId, range);
    } catch (VoiceRangeNotSatisfiableException unsatisfiable) {
      return rangeNotSatisfiable(unsatisfiable.contentLength());
    }
    long length = media.resource().contentLength();
    long start = range.getRangeStart(length);
    long end = range.getRangeEnd(length);
    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        .headers(mediaHeaders(media))
        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
        .header(HttpHeaders.CONTENT_LENGTH, Long.toString(end - start + 1))
        .body(new InputStreamResource(media.resource().inputStream()));
  }

  /**
   * A malformed header cannot be resolved, but the 416 must still carry the total length
   * (RFC 7233 §4.4): probe the resource — ownership/status rejections from the module take
   * precedence — then close it and answer the empty 416.
   */
  private ResponseEntity<InputStreamResource> rangeNotSatisfiable(
      CurrentUser user, UUID sessionId, UUID speechId) {
    QuestionSpeechMedia media = module.open(user, sessionId, speechId, null);
    long length = media.resource().contentLength();
    try {
      media.close();
    } catch (IOException ignored) {
      // nothing is streamed; only the length matters for the 416 header
    }
    return rangeNotSatisfiable(length);
  }

  private static ResponseEntity<InputStreamResource> rangeNotSatisfiable(long length) {
    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
        .build();
  }

  private static HttpHeaders mediaHeaders(QuestionSpeechMedia media) {
    HttpHeaders headers = new HttpHeaders();
    String mediaType = media.resource().mediaType();
    if (mediaType != null) {
      headers.setContentType(MediaType.parseMediaType(mediaType));
    }
    headers.set(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISPOSITION_INLINE);
    headers.set("X-Content-Type-Options", "nosniff");
    headers.setETag(media.etag());
    headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    headers.set(HttpHeaders.ACCEPT_RANGES, ACCEPT_RANGES_BYTES);
    return headers;
  }

  private static boolean hasRange(String rangeHeader) {
    return rangeHeader != null && !rangeHeader.isBlank();
  }
}
