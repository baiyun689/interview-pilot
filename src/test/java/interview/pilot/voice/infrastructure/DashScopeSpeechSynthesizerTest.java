package interview.pilot.voice.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import interview.pilot.voice.application.SpeechSynthesisFailedException;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.SynthesizedSpeech;
import interview.pilot.voice.application.VoiceProfile;
import interview.pilot.voice.config.VoiceProperties.Asr;

/**
 * WireMock tests for the DashScope CosyVoice non-realtime adapter (plan §11): the request
 * shape is pinned against the documented HTTP synthesis call (POST the multimodal-generation
 * endpoint with model/voice/text), BOTH documented response shapes are covered — the JSON
 * envelope with a temporary {@code output.audio.url} (bounded download) and the raw audio
 * body — and the error taxonomy is the same seam the recognition adapter established:
 * network/429/5xx/timeout are retryable (on both the synthesis call and the download);
 * 4xx/empty audio/oversized audio are deterministic. The TTS config carries no credentials:
 * the adapter authenticates with the ASR api-key against the shared DashScope gateway base
 * URL. The JSON field names follow the plan §22 citation and remain to be smoke-tested with
 * a real key (Task 12).
 */
class DashScopeSpeechSynthesizerTest {
  private static final String COSYVOICE_ENDPOINT = "/services/audio/tts/SpeechSynthesizer";
  private static final String TEXT = "请自我介绍";
  private static final byte[] AUDIO = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);
  private static final long MAX_AUDIO_BYTES = 4096;
  private static final Asr ASR = new Asr(
      "dashscope", "http://localhost:PORT", "sk-test",
      "fun-asr-flash-2026-06-15", Duration.ofSeconds(30));
  private static final VoiceProfile PROFILE =
      new VoiceProfile("dashscope", "cosyvoice-v3-flash", "longanyang");

  private WireMockServer wireMock;
  private DashScopeSpeechSynthesizer synthesizer;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
    synthesizer = new DashScopeSpeechSynthesizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofSeconds(30)),
        MAX_AUDIO_BYTES);
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  @Test
  void postsTheModelVoiceAndTextAndReturnsTheRawAudioBytes() {
    String body = """
        {
          "model": "cosyvoice-v3-flash",
          "input": {
            "text": "请自我介绍",
            "voice": "longanyang",
            "format": "mp3"
          }
        }
        """;
    wireMock.stubFor(post(urlEqualTo(COSYVOICE_ENDPOINT))
        .withRequestBody(equalToJson(body))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "audio/mpeg")
            .withBody(AUDIO)));

    SynthesizedSpeech speech = synthesizer.synthesize(TEXT, PROFILE);

    assertThat(speech.audio()).isEqualTo(AUDIO);
    assertThat(speech.mediaType()).isEqualTo("audio/mpeg");
    assertThat(speech.providerRequestId()).isNull();
    wireMock.verify(postRequestedFor(urlEqualTo(COSYVOICE_ENDPOINT))
        .withHeader("Authorization", containing("Bearer sk-test")));
  }

  // ---------------------------------------------------------------- JSON + temp URL shape

  @Test
  void jsonEnvelopeWithAudioUrlDownloadsTheAudioBounded() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"output": {"audio": {"url": "http://localhost:%d/audio.mp3"}}}
                """.formatted(wireMock.port()))));
    wireMock.stubFor(get(urlEqualTo("/audio.mp3"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "audio/mpeg")
            .withBody(AUDIO)));

    SynthesizedSpeech speech = synthesizer.synthesize(TEXT, PROFILE);

    assertThat(speech.audio()).isEqualTo(AUDIO);
    assertThat(speech.mediaType()).isEqualTo("audio/mpeg");
    wireMock.verify(getRequestedFor(urlEqualTo("/audio.mp3")));
  }

  @Test
  void jsonEnvelopeWithoutAudioUrlIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"output\": {\"audio\": {}}}")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void unparseableJsonEnvelopeIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("not-json")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void audioUrlDownloadRejectionIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"output": {"audio": {"url": "http://localhost:%d/audio.mp3"}}}
                """.formatted(wireMock.port()))));
    wireMock.stubFor(get(urlEqualTo("/audio.mp3"))
        .willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void audioUrlDownloadServerErrorIsRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"output": {"audio": {"url": "http://localhost:%d/audio.mp3"}}}
                """.formatted(wireMock.port()))));
    wireMock.stubFor(get(urlEqualTo("/audio.mp3"))
        .willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisRetryableException.class);
  }

  @Test
  void oversizedAudioUrlDownloadIsDeterministic() {
    var tiny = new DashScopeSpeechSynthesizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofSeconds(30)), 4);
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"output": {"audio": {"url": "http://localhost:%d/audio.bin"}}}
                """.formatted(wireMock.port()))));
    wireMock.stubFor(get(urlEqualTo("/audio.bin"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "audio/mpeg")
            .withBody(new byte[] {1, 2, 3, 4, 5})));

    assertThatThrownBy(() -> tiny.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  // ---------------------------------------------------------------- error taxonomy

  @Test
  void rateLimitIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(429).withBody("{\"code\":\"Throttling\"}")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisRetryableException.class);
  }

  @Test
  void serverErrorIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(503).withBody("{\"code\":\"ServiceUnavailable\"}")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisRetryableException.class);
  }

  @Test
  void readTimeoutIsOperationalAndRetryable() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
    var slowSynthesizer = new DashScopeSpeechSynthesizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofMillis(300)),
        MAX_AUDIO_BYTES);

    assertThatThrownBy(() -> slowSynthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisRetryableException.class);
  }

  @Test
  void clientRejectionIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(400)
            .withBody("{\"code\":\"InvalidParameter\",\"message\":\"bad voice\"}")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void unauthorizedIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(401).withBody("{\"code\":\"InvalidApiKey\"}")));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void emptyAudioBodyIsDeterministic() {
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "audio/mpeg")
            .withBody(new byte[0])));

    assertThatThrownBy(() -> synthesizer.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void oversizedRawAudioBodyIsDeterministicEvenWithoutContentLength() {
    var tiny = new DashScopeSpeechSynthesizer(
        withPort(ASR, wireMock.port()), restClient(wireMock.port(), Duration.ofSeconds(30)), 4);
    wireMock.stubFor(post(urlEqualTo(DashScopeSpeechSynthesizer.ENDPOINT_PATH))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "audio/mpeg")
            .withBody(new byte[] {1, 2, 3, 4, 5})));

    assertThatThrownBy(() -> tiny.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisFailedException.class);
  }

  @Test
  void connectTimeoutIsOperationalAndRetryable() {
    var unreachable = new DashScopeSpeechSynthesizer(
        new Asr(ASR.provider(), "http://10.255.255.1:9999", ASR.apiKey(), ASR.model(),
            Duration.ofMillis(300)),
        restClient("http://10.255.255.1:9999", Duration.ofMillis(300)),
        MAX_AUDIO_BYTES);

    assertThatThrownBy(() -> unreachable.synthesize(TEXT, PROFILE))
        .isInstanceOf(SpeechSynthesisRetryableException.class);
  }

  private static Asr withPort(Asr asr, int port) {
    return new Asr(asr.provider(), "http://localhost:" + port,
        asr.apiKey(), asr.model(), asr.timeout());
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
}
