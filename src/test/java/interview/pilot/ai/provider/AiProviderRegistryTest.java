package interview.pilot.ai.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.github.tomakehurst.wiremock.WireMockServer;
import interview.pilot.common.ratelimit.LlmConcurrencyAdmission;
import interview.pilot.common.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiProviderRegistryTest {
  private WireMockServer wireMock;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  @Test
  void enabledProviderWithBlankDisplayNameDoesNotBuildAClient() {
    var provider = new AiProviderProperties.Provider(
        " ",
        URI.create(wireMock.baseUrl()),
        "test-key",
        "test-model",
        true,
        Duration.ofSeconds(1),
        Map.of());
    var registry = registry(
        new AiProviderProperties("blank-name", Map.of("blank-name", provider), 2));

    assertThatThrownBy(() -> registry.generate("blank-name", "system", "user"))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("incomplete");
    wireMock.verify(0, postRequestedFor(anyUrl()));
  }

  @Test
  void enabledCompleteProviderCallsTheOpenAiCompatibleEndpoint() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
        .willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1720000000,
                  "model": "test-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "OK"},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """)));
    var provider = new AiProviderProperties.Provider(
        "Test Provider",
        URI.create(wireMock.baseUrl()),
        "test-key",
        "test-model",
        true,
        Duration.ofSeconds(1),
        Map.of());
    var registry = registry(
        new AiProviderProperties("test", Map.of("test", provider), 2));

    AiProviderRegistry.Completion completion = registry.generate("test", "system", "user");

    assertThat(completion.content()).isEqualTo("OK");
    assertThat(completion.model()).isEqualTo("test-model");
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
  }

  @Test
  void providerExtraBodyFlattensIntoTheChatCompletionRequest() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
        .willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"id":"chatcmpl-test","object":"chat.completion","created":1720000000,
                 "model":"test-model","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,
                 "completion_tokens":1,"total_tokens":2}}
                """)));
    var provider = new AiProviderProperties.Provider(
        "Test Provider",
        URI.create(wireMock.baseUrl()),
        "test-key",
        "test-model",
        true,
        Duration.ofSeconds(1),
        Map.of("enable_thinking", false));
    var registry = registry(
        new AiProviderProperties("test", Map.of("test", provider), 2));

    registry.generate("test", "system", "user");

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
        .withRequestBody(matchingJsonPath("$.enable_thinking", equalTo("false"))));
  }

  @Test
  void rejectsAServerSideSessionModelSnapshotThatNoLongerMatchesConfiguration() {
    var provider = new AiProviderProperties.Provider(
        "Test Provider", URI.create(wireMock.baseUrl()), "test-key", "new-model", true,
        Duration.ofSeconds(1), Map.of());
    var registry = registry(
        new AiProviderProperties("test", Map.of("test", provider), 2));

    assertThatThrownBy(() -> registry.generate(
            "test", "snapshotted-model", "system", "user"))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("snapshot");
    wireMock.verify(0, postRequestedFor(anyUrl()));
  }

  @Test
  void actualProviderFailureStillReleasesExactlyTheOwnedAdmission() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    var provider = new AiProviderProperties.Provider(
        "Test Provider", URI.create(wireMock.baseUrl()), "test-key", "test-model", true,
        Duration.ofSeconds(1), Map.of());
    var properties = new AiProviderProperties("test", Map.of("test", provider), 2);
    var admission = mock(LlmConcurrencyAdmission.class);
    when(admission.acquire()).thenReturn("owned-token");
    var metrics = new SimpleMeterRegistry();
    var registry = new AiProviderRegistry(properties, admission, new AiMetrics(metrics));

    assertThatThrownBy(() -> registry.generate("test", "system", "user"))
        .isInstanceOf(RuntimeException.class);

    verify(admission).release("owned-token");
    assertThat(metrics.get("interview_pilot.ai.calls").tag("outcome", "failure")
        .counter().count()).isEqualTo(1);
  }

  @Test
  void metricsFailureDoesNotChangeSuccessfulResultAndStillReleasesExactlyOnce() {
    stubSuccessfulCompletion();
    var provider = provider();
    var properties = new AiProviderProperties("test", Map.of("test", provider), 2);
    var admission = mock(LlmConcurrencyAdmission.class);
    when(admission.acquire()).thenReturn("owned-token");
    var metrics = mock(AiMetrics.class);
    doThrow(new IllegalStateException("metrics-failed"))
        .when(metrics).aiCall(anyString(), anyString(), any(Duration.class));
    var registry = new AiProviderRegistry(properties, admission, metrics);

    AiProviderRegistry.Completion result = registry.generate("test", "system", "user");

    assertThat(result.content()).isEqualTo("OK");
    verify(admission, times(1)).release("owned-token");
  }

  @Test
  void metricsFailureDoesNotMaskProviderFailureAndStillReleasesExactlyOnce() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    var properties = new AiProviderProperties("test", Map.of("test", provider()), 2);
    var admission = mock(LlmConcurrencyAdmission.class);
    when(admission.acquire()).thenReturn("owned-token");
    var metrics = mock(AiMetrics.class);
    doThrow(new IllegalStateException("metrics-failed"))
        .when(metrics).aiCall(anyString(), anyString(), any(Duration.class));
    var registry = new AiProviderRegistry(properties, admission, metrics);

    assertThatThrownBy(() -> registry.generate("test", "system", "user"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageNotContaining("metrics-failed");
    verify(admission, times(1)).release("owned-token");
  }

  private AiProviderProperties.Provider provider() {
    return new AiProviderProperties.Provider(
        "Test Provider", URI.create(wireMock.baseUrl()), "test-key", "test-model", true,
        Duration.ofSeconds(1), Map.of());
  }

  private void stubSuccessfulCompletion() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
        .willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"id":"chatcmpl-test","object":"chat.completion","created":1720000000,
                 "model":"test-model","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,
                 "completion_tokens":1,"total_tokens":2}}
                """)));
  }

  private AiProviderRegistry registry(AiProviderProperties properties) {
    var admission = mock(LlmConcurrencyAdmission.class);
    when(admission.acquire()).thenReturn("test-token");
    return new AiProviderRegistry(
        properties, admission, new AiMetrics(new SimpleMeterRegistry()));
  }
}
