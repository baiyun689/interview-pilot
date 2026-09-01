package interview.pilot.voice.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import interview.pilot.voice.application.RecognitionContext;
import interview.pilot.voice.application.Transcript;
import interview.pilot.voice.application.VoiceTranscriptionFailedException;
import interview.pilot.voice.application.VoiceTranscriptionRetryableException;
import interview.pilot.voice.config.VoiceProperties.Asr;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.storage.InMemoryVoiceMediaStore;

/**
 * WireMock tests for the DashScope adapter's error taxonomy — the seam Task 11/12 metrics
 * depend on: network/429/5xx/timeout are retryable; 4xx/empty/unparseable are deterministic.
 * The request shape is pinned against the documented synchronous fun-asr-flash call
 * (POST /api/v1/services/aigc/multimodal-generation/generation, base64 data URL audio, no
 * vocabulary — fun-asr-flash only accepts a pre-compiled vocabulary_id, which this app does
 * not manage, so hints are ignored silently per plan §10).
 */
class DashScopeSpeechRecognizerTest {
  private static final String MEDIA = "fake audio bytes";
  private static final Asr ASR = new Asr(
      "dashscope", "http://localhost:PORT", "sk-test",
      "fun-asr-flash-2026-06-15", Duration.ofSeconds(30));

  private WireMockServer wireMock;
  private InMemoryVoiceMediaStore media;
  private DashScopeSpeechRecognizer recognizer;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
    media = new InMemoryVoiceMediaStore();
    recognizer = new DashScopeSpeechRecognizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofSeconds(30)), media);
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  @Test
  void postsTheModelAndBase64AudioToTheSynchronousGenerationEndpoint() {
    String encoded = Base64.getEncoder().encodeToString(MEDIA.getBytes(StandardCharsets.UTF_8));
    String body = """
        {
          "model": "fun-asr-flash-2026-06-15",
          "input": {
            "messages": [
              {
                "role": "user",
                "content": [
                  {
                    "type": "input_audio",
                    "input_audio": {
                      "data": "data:audio/webm;base64,%s"
                    }
                  }
                ]
              }
            ]
          },
          "parameters": {"format": "webm"}
        }
        """.formatted(encoded);
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .withRequestBody(equalToJson(body))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json;charset=UTF-8")
            .withBody(successBody("你好，我是候选人", "req-1"))));

    Transcript transcript = recognizer.transcribe(media("audio/webm"), context());

    assertThat(transcript.text()).isEqualTo("你好，我是候选人");
    assertThat(transcript.providerRequestId()).isEqualTo("req-1");
    assertThat(transcript.model()).isEqualTo("fun-asr-flash-2026-06-15");
    wireMock.verify(postRequestedFor(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .withHeader("Authorization", containing("Bearer sk-test"))
        .withHeader("X-DashScope-SSE", containing("disable")));
  }

  @Test
  void fallsBackToTheSentenceFieldAndDefaultsTheFormatWhenMediaTypeIsUnknown() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json;charset=UTF-8")
            .withBody("""
                {"request_id": "req-2", "output": {"sentence": {"text": "仅句子文本"}}}
                """)));

    Transcript transcript = recognizer.transcribe(media(null), context());

    assertThat(transcript.text()).isEqualTo("仅句子文本");
    assertThat(transcript.providerRequestId()).isEqualTo("req-2");
    wireMock.verify(postRequestedFor(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .withRequestBody(containing("\"format\":\"wav\""))
        .withRequestBody(containing("data:audio/wav;base64,")));
  }

  @Test
  void rateLimitIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(429).withBody(errorBody("Throttling", "rate limited"))));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionRetryableException.class);
  }

  @Test
  void serverErrorIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(503).withBody(errorBody("ServiceUnavailable", "down"))));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionRetryableException.class);
  }

  @Test
  void readTimeoutIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
    var slowRecognizer = new DashScopeSpeechRecognizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofMillis(300)), media);

    assertThatThrownBy(() -> slowRecognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionRetryableException.class);
  }

  @Test
  void clientRejectionIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(400).withBody(errorBody("InvalidParameter", "bad format"))));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionFailedException.class);
  }

  @Test
  void unauthorizedIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(401).withBody(errorBody("InvalidApiKey", "bad key"))));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionFailedException.class);
  }

  @Test
  void connectTimeoutIsOperationalAndRetryable() {
    // 10.255.255.1 is non-routable: the connect phase hangs until the 300ms connect timeout
    // (an instant refusal on some networks is the same ResourceAccessException family and
    // stays retryable either way).
    var unreachable = new DashScopeSpeechRecognizer(
        new Asr(ASR.provider(), "http://10.255.255.1:9999", ASR.apiKey(), ASR.model(),
            Duration.ofMillis(300)),
        restClient("http://10.255.255.1:9999", Duration.ofMillis(300)), media);

    assertThatThrownBy(() -> unreachable.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionRetryableException.class);
  }

  @Test
  void emptyTranscriptIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withBody("""
                {"request_id": "req-3", "output": {"text": ""}}
                """)));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionFailedException.class);
  }

  @Test
  void unparseableResponseIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200).withBody("not-json")));

    assertThatThrownBy(() -> recognizer.transcribe(media("audio/webm"), context()))
        .isInstanceOf(VoiceTranscriptionFailedException.class);
  }

  @Test
  void vocabularyHintsAreIgnoredSilentlyAndNeverReachTheRequest() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200).withBody(successBody("text", "req-4"))));

    recognizer.transcribe(media("audio/webm"),
        new RecognitionContext(List.of("Spring", "事务", "MySQL")));

    wireMock.verify(postRequestedFor(urlEqualTo(DashScopeSpeechRecognizer.ENDPOINT_PATH))
        .withRequestBody(containing("\"format\":\"webm\"")));
  }

  private StoredVoiceMedia media(String mediaType) {
    var key = new VoiceMediaKey(
        java.util.UUID.randomUUID(), 7L, VoiceMediaKind.RECORDING, java.util.UUID.randomUUID());
    var stored = media.store(key, new java.io.ByteArrayInputStream(
        MEDIA.getBytes(StandardCharsets.UTF_8)), 1024);
    return new StoredVoiceMedia(
        stored.storageKey(), stored.sha256(), stored.sizeBytes(), mediaType, null);
  }

  private static RecognitionContext context() {
    return new RecognitionContext(List.of());
  }

  private static RestClient restClient(int port, Duration timeout) {
    return restClient("http://localhost:" + port, timeout);
  }

  private static RestClient restClient(String baseUrl, Duration timeout) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  private static Asr withPort(Asr asr, int port) {
    return new Asr(asr.provider(), "http://localhost:" + port,
        asr.apiKey(), asr.model(), asr.timeout());
  }

  private static String successBody(String text, String requestId) {
    return "{\"request_id\": \"" + requestId + "\", \"output\": {\"text\": \"" + text + "\"}}";
  }

  private static String errorBody(String code, String message) {
    return "{\"code\": \"" + code + "\", \"message\": \"" + message + "\"}";
  }
}
