package interview.pilot.voice.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import interview.pilot.voice.application.RecognitionContext;
import interview.pilot.voice.application.SpeechRecognizer;
import interview.pilot.voice.application.Transcript;
import interview.pilot.voice.application.VoiceTranscriptionFailedException;
import interview.pilot.voice.application.VoiceTranscriptionRetryableException;
import interview.pilot.voice.config.VoiceProperties.Asr;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.storage.VoiceMediaStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DashScope synchronous speech recognition (plan §10): the fun-asr-flash family accepts short
 * audio via a single synchronous request carrying a base64 data URL, which fits this app's
 * local deployment (no public media URL — the plan's documented choice over the async
 * transcription pipeline, which only accepts public URLs).
 *
 * <p>Endpoint (verified against the Model Studio docs at help.aliyun.com, "非实时语音识别
 * (Fun-ASR-Flash) API参考"): {@code POST {base-url}/api/v1/services/aigc/multimodal-generation/generation}
 * with {@code X-DashScope-SSE: disable}; the transcript lives at {@code output.text} (with a
 * {@code output.sentence.text} fallback) and {@code request_id} at the root.
 *
 * <p>Error taxonomy — the seam Task 11/12 metrics depend on: connection/read timeouts, 429 and
 * 5xx are operational ({@link VoiceTranscriptionRetryableException}); any other 4xx, an empty
 * transcript and an unparseable response are deterministic
 * ({@link VoiceTranscriptionFailedException}).
 *
 * <p>Vocabulary hints are ignored silently: fun-asr-flash only supports a pre-compiled
 * {@code vocabulary_id} list managed through the separate customization API — there is no
 * inline hotword parameter — so passing nothing keeps the request shape stable (plan §10).
 */
public class DashScopeSpeechRecognizer implements SpeechRecognizer {

  static final String ENDPOINT_PATH = "/services/aigc/multimodal-generation/generation";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Asr config;
  private final RestClient rest;
  private final VoiceMediaStore mediaStore;

  public DashScopeSpeechRecognizer(Asr config, RestClient rest, VoiceMediaStore mediaStore) {
    this.config = config;
    this.rest = rest;
    this.mediaStore = mediaStore;
  }

  @Override
  public Transcript transcribe(StoredVoiceMedia audio, RecognitionContext context) {
    String base64 = readBase64(audio);
    String response;
    try {
      response = rest.post()
          .uri(config.baseUrl() + ENDPOINT_PATH)
          .header("Authorization", "Bearer " + credential())
          .header("Content-Type", "application/json")
          .header("X-DashScope-SSE", "disable")
          .body(requestBody(audio, base64))
          .retrieve()
          .body(String.class);
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().is5xxServerError()
          || exception.getStatusCode().value() == 429) {
        throw new VoiceTranscriptionRetryableException(
            "DashScope recognition temporarily unavailable (" + exception.getStatusCode() + ")",
            0);
      }
      throw new VoiceTranscriptionFailedException(
          "DashScope rejected the recognition request (" + exception.getStatusCode() + ")");
    } catch (RestClientException exception) {
      // Connection/read timeouts and extraction failures are operational: retrying a slow or
      // unreachable provider is the recovery path, unlike a 4xx rejection.
      throw new VoiceTranscriptionRetryableException(
          "DashScope recognition was unreachable", 0);
    }
    return parse(response);
  }

  /**
   * The config allows an api key OR a workspace id (VoiceProperties.asrComplete); a workspace
   * that authenticates by id uses it here — the Authorization header must never be "Bearer null".
   */
  private String credential() {
    if (config.apiKey() != null && !config.apiKey().isBlank()) {
      return config.apiKey();
    }
    return config.workspaceId() == null ? "" : config.workspaceId();
  }

  private String readBase64(StoredVoiceMedia audio) {
    try (VoiceMediaResource resource = mediaStore.open(audio.storageKey())) {
      byte[] bytes = resource.inputStream().readAllBytes();
      return Base64.getEncoder().encodeToString(bytes);
    } catch (IOException exception) {
      throw new VoiceMediaStorageException(
          "Unable to read the voice media for recognition", exception);
    }
  }

  private Map<String, Object> requestBody(StoredVoiceMedia audio, String base64) {
    String mediaType = audio.mediaType();
    Map<String, Object> audioContent = Map.of(
        "type", "input_audio",
        "input_audio", Map.of("data", dataUrl(mediaType, base64)));
    Map<String, Object> userMessage = Map.of(
        "role", "user",
        "content", List.of(audioContent));
    Map<String, Object> input = Map.of("messages", List.of(userMessage));
    Map<String, Object> parameters = Map.of("format", format(mediaType));
    return Map.of("model", config.model(), "input", input, "parameters", parameters);
  }

  private static String dataUrl(String mediaType, String base64) {
    String mime = mediaType == null || mediaType.isBlank() ? "audio/wav" : mediaType;
    return "data:" + mime + ";base64," + base64;
  }

  private static String format(String mediaType) {
    if (mediaType == null || !mediaType.startsWith("audio/")) {
      return "wav";
    }
    return mediaType.substring("audio/".length());
  }

  private Transcript parse(String response) {
    JsonNode root;
    try {
      root = JSON.readTree(response);
    } catch (JacksonException exception) {
      throw new VoiceTranscriptionFailedException(
          "DashScope recognition response was not parseable");
    }
    String text = firstNonBlank(
        root.path("output").path("text").asText(null),
        root.path("output").path("sentence").path("text").asText(null));
    if (text == null) {
      throw new VoiceTranscriptionFailedException(
          "DashScope returned no transcript text");
    }
    String requestId = root.path("request_id").asText(null);
    return new Transcript(text, requestId, config.model());
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
