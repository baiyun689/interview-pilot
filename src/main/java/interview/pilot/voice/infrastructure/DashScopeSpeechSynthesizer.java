package interview.pilot.voice.infrastructure;

import java.util.Map;
import java.util.Objects;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import interview.pilot.voice.application.SpeechSynthesisFailedException;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.SpeechSynthesizer;
import interview.pilot.voice.application.SynthesizedSpeech;
import interview.pilot.voice.application.VoiceProfile;
import interview.pilot.voice.config.VoiceProperties.Asr;

/**
 * DashScope CosyVoice non-realtime synthesis (plan §11): the HTTP form of CosyVoice accepts
 * full-text input and returns the generated audio directly (the WebSocket form is for
 * low-latency streaming, which this release does not need — the audio is downloaded into
 * memory and streamed into the private store instead of depending on 24-hour temporary URLs).
 *
 * <p>Endpoint (assumed same as the recognizer's, per the plan's cited non-realtime TTS guide
 * at help.aliyun.com): {@code POST {base-url}/api/v1/services/aigc/multimodal-generation/generation}
 * with the model/voice/text under {@code input}; the response body is the raw audio and the
 * {@code Content-Type} header reports the format. The 200-with-non-audio case (e.g. a JSON
 * error body) is not handled here: the synthesis handler's media probe rejects it
 * deterministically.
 *
 * <p>Credentials: the TTS configuration carries NO credentials of its own (Task 2 decision,
 * confirmed in review) — this adapter reuses {@code asr.baseUrl} + {@code asr.apiKey}: a VOICE
 * session always has ASR configured ({@code VoiceProperties} validation requires the API key
 * whenever voice is enabled), and the TTS endpoint lives under the same DashScope gateway.
 *
 * <p>Error taxonomy — the seam Task 12 metrics depend on: connection/read timeouts, 429 and
 * 5xx are operational ({@link SpeechSynthesisRetryableException}); any other 4xx and an empty
 * audio body are deterministic ({@link SpeechSynthesisFailedException}).
 */
public class DashScopeSpeechSynthesizer implements SpeechSynthesizer {

  static final String ENDPOINT_PATH = "/services/aigc/multimodal-generation/generation";

  private final Asr asr;
  private final RestClient rest;

  public DashScopeSpeechSynthesizer(Asr asr, RestClient rest) {
    this.asr = Objects.requireNonNull(asr, "asr");
    this.rest = Objects.requireNonNull(rest, "rest");
  }

  @Override
  public SynthesizedSpeech synthesize(String text, VoiceProfile profile) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(profile, "profile");
    byte[] audio;
    String contentType;
    try {
      var response = rest.post()
          .uri(asr.baseUrl() + ENDPOINT_PATH)
          .header("Authorization", "Bearer " + asr.apiKey())
          .header("Content-Type", "application/json")
          .body(requestBody(profile, text))
          .retrieve()
          .toEntity(byte[].class);
      audio = response.getBody();
      var mediaType = response.getHeaders().getContentType();
      contentType = mediaType == null ? null : mediaType.toString();
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().is5xxServerError()
          || exception.getStatusCode().value() == 429) {
        throw new SpeechSynthesisRetryableException(
            "DashScope synthesis temporarily unavailable (" + exception.getStatusCode() + ")",
            0);
      }
      throw new SpeechSynthesisFailedException(
          "DashScope rejected the synthesis request (" + exception.getStatusCode() + ")");
    } catch (RestClientException exception) {
      // Connection/read timeouts and extraction failures are operational: retrying a slow or
      // unreachable provider is the recovery path, unlike a 4xx rejection.
      throw new SpeechSynthesisRetryableException("DashScope synthesis was unreachable", 0);
    }
    if (audio == null || audio.length == 0) {
      throw new SpeechSynthesisFailedException("DashScope returned no audio");
    }
    // The non-realtime binary response exposes no request id header, so providerRequestId
    // stays null (the question_speech column is nullable; the fake adapter fills it in tests).
    return new SynthesizedSpeech(audio, contentType, null);
  }

  private static Map<String, Object> requestBody(VoiceProfile profile, String text) {
    return Map.of(
        "model", profile.model(),
        "input", Map.of(
            "text", text,
            "voice", profile.voice()),
        "parameters", Map.of("format", "mp3"));
  }
}
