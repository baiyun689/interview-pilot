package interview.pilot.voice.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import interview.pilot.voice.application.SpeechSynthesisFailedException;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.SpeechSynthesizer;
import interview.pilot.voice.application.SynthesizedSpeech;
import interview.pilot.voice.application.VoiceProfile;
import interview.pilot.voice.config.VoiceProperties.Asr;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DashScope CosyVoice non-realtime synthesis (plan §11). The provider's documented
 * non-realtime HTTP form returns the audio in ONE of two shapes, both handled here:
 *
 * <ul>
 *   <li>a JSON envelope whose {@code output.audio.url} points at the generated audio on a
 *       temporary (24h) URL — the adapter downloads it bounded (size cap and timeout) into
 *       memory and passes the bytes on;</li>
 *   <li>the raw audio bytes as the response body (the streamed-audio form) — passed through
 *       as-is; the media store's probe remains authoritative for the real format.</li>
 * </ul>
 *
 * <p>Shape assumption: the JSON/URL fields are taken from the plan §22 citation of the
 * non-realtime TTS user guide at help.aliyun.com and have NOT been verified against a live
 * endpoint — Task 12's smoke test with a real API key must confirm the field names and the
 * preferred shape. Wrong shapes fail safely: a 200 whose body is not playable audio is
 * rejected deterministically by the synthesis handler's media probe.
 *
 * <p>Credentials: the TTS configuration carries NO credentials of its own (Task 2 decision,
 * confirmed in review) — this adapter reuses {@code asr.baseUrl} + {@code asr.apiKey}: a VOICE
 * session always has ASR configured ({@code VoiceProperties} validation requires the API key
 * whenever voice is enabled), and the TTS endpoint lives under the same DashScope gateway.
 *
 * <p>Error taxonomy — the seam Task 12 metrics depend on: connection/read timeouts, 429 and
 * 5xx (on the synthesis call AND the audio download) are operational
 * ({@link SpeechSynthesisRetryableException}); any other 4xx, an empty body, an unparseable
 * envelope, a missing audio URL and audio over the size cap are deterministic
 * ({@link SpeechSynthesisFailedException}).
 */
public class DashScopeSpeechSynthesizer implements SpeechSynthesizer {

  /** DashScope's non-streaming CosyVoice HTTP endpoint. */
  static final String ENDPOINT_PATH = "/services/audio/tts/SpeechSynthesizer";
  /** The synthesis response envelope is tiny; anything larger is not the documented JSON shape. */
  private static final long JSON_ENVELOPE_MAX_BYTES = 64 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Asr asr;
  private final RestClient rest;
  private final long maxAudioBytes;

  public DashScopeSpeechSynthesizer(Asr asr, RestClient rest, long maxAudioBytes) {
    this.asr = Objects.requireNonNull(asr, "asr");
    this.rest = Objects.requireNonNull(rest, "rest");
    if (maxAudioBytes <= 0) {
      throw new IllegalArgumentException("maxAudioBytes must be positive");
    }
    this.maxAudioBytes = maxAudioBytes;
  }

  @Override
  public SynthesizedSpeech synthesize(String text, VoiceProfile profile) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(profile, "profile");
    try {
      return rest.post()
          .uri(asr.baseUrl() + ENDPOINT_PATH)
          .header("Authorization", "Bearer " + asr.apiKey())
          .header("Content-Type", "application/json")
          .body(requestBody(profile, text))
          .exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is5xxServerError() || status.value() == 429) {
              throw new SpeechSynthesisRetryableException(
                  "DashScope synthesis temporarily unavailable (" + status + ")", 0);
            }
            if (status.isError()) {
              throw new SpeechSynthesisFailedException(
                  "DashScope rejected the synthesis request (" + status + ")");
            }
            return synthesisBody(response);
          });
    } catch (SpeechSynthesisRetryableException | SpeechSynthesisFailedException exception) {
      throw exception;
    } catch (RestClientException exception) {
      // Connection/read timeouts and extraction failures are operational (body-read
      // IOExceptions surface as ResourceAccessException, a RestClientException): retrying a
      // slow or unreachable provider is the recovery path, unlike a 4xx rejection.
      throw new SpeechSynthesisRetryableException("DashScope synthesis was unreachable", 0);
    }
  }

  private SynthesizedSpeech synthesisBody(ClientHttpResponse response) throws IOException {
    MediaType contentType = response.getHeaders().getContentType();
    if (contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
      return downloadFromJson(response);
    }
    // Raw audio bytes (the streamed-audio form): read bounded into memory, then the store's
    // probe is authoritative for the real media type.
    byte[] audio = readBounded(response, maxAudioBytes);
    if (audio.length == 0) {
      throw new SpeechSynthesisFailedException("DashScope returned no audio");
    }
    return new SynthesizedSpeech(audio, contentType == null ? null : contentType.toString(), null);
  }

  /** JSON envelope: {@code output.audio.url} points at the audio; download it bounded. */
  private SynthesizedSpeech downloadFromJson(ClientHttpResponse response) throws IOException {
    byte[] envelope = readBounded(response, JSON_ENVELOPE_MAX_BYTES);
    Download download = downloadAudio(parseAudioUrl(envelope));
    if (download.audio().length == 0) {
      throw new SpeechSynthesisFailedException("DashScope returned no audio");
    }
    return new SynthesizedSpeech(download.audio(), download.mediaType(), null);
  }

  private Download downloadAudio(String url) {
    try {
      return rest.get()
          // DashScope returns a pre-signed OSS URL. Passing a String makes RestClient treat
          // it as a URI template, which can alter percent-encoded signature parameters.
          .uri(URI.create(url))
          .exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is5xxServerError() || status.value() == 429) {
              throw new SpeechSynthesisRetryableException(
                  "DashScope audio download temporarily unavailable (" + status + ")", 0);
            }
            if (status.isError()) {
              throw new SpeechSynthesisFailedException(
                  "DashScope audio download was rejected (" + status + ")");
            }
            MediaType contentType = response.getHeaders().getContentType();
            return new Download(
                readBounded(response, maxAudioBytes),
                contentType == null ? null : contentType.toString());
          });
    } catch (SpeechSynthesisRetryableException | SpeechSynthesisFailedException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw new SpeechSynthesisRetryableException("DashScope audio download was unreachable", 0);
    }
  }

  private static String parseAudioUrl(byte[] envelope) {
    JsonNode root;
    try {
      root = JSON.readTree(envelope);
    } catch (JacksonException exception) {
      throw new SpeechSynthesisFailedException(
          "DashScope synthesis response was not parseable");
    }
    String url = root.path("output").path("audio").path("url").asText(null);
    if (url == null || url.isBlank()) {
      throw new SpeechSynthesisFailedException("DashScope returned no audio URL");
    }
    return url;
  }

  /**
   * Capped in-memory read: the Content-Length is pre-checked when present, and the stream
   * itself is bounded — the store's own maxBytes check must never be the first line of
   * defense against an unbounded provider response.
   */
  private static byte[] readBounded(ClientHttpResponse response, long maxBytes)
      throws IOException {
    long contentLength = response.getHeaders().getContentLength();
    if (contentLength > maxBytes) {
      throw tooLarge();
    }
    try (InputStream input = response.getBody()) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(
          contentLength > 0 ? (int) contentLength : 1024);
      byte[] chunk = new byte[8192];
      long copied = 0;
      for (int read; (read = input.read(chunk)) >= 0;) {
        if (copied + read > maxBytes) {
          throw tooLarge();
        }
        buffer.write(chunk, 0, read);
        copied += read;
      }
      return buffer.toByteArray();
    }
  }

  private static SpeechSynthesisFailedException tooLarge() {
    return new SpeechSynthesisFailedException(
        "DashScope audio exceeds the configured media limit");
  }

  private static Map<String, Object> requestBody(VoiceProfile profile, String text) {
    return Map.of(
        "model", profile.model(),
        "input", Map.of(
            "text", text,
            "voice", profile.voice(),
            "format", "mp3"));
  }

  private record Download(byte[] audio, String mediaType) {}
}
