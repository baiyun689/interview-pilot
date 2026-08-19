package interview.pilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.model.AiResponse;
import interview.pilot.ai.provider.AiProviderProperties;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.domain.JobRequirements;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

class StructuredOutputInvokerTest {
  private static final String SECRET_IN_INVALID_OUTPUT = "sk-raw-response-secret";

  private AiGateway gateway;
  private SimpleMeterRegistry meterRegistry;
  private StructuredOutputInvoker invoker;
  private AiRequest request;

  @BeforeEach
  void setUp() {
    gateway = mock(AiGateway.class);
    meterRegistry = new SimpleMeterRegistry();
    var properties = new AiProviderProperties(
        "deepseek",
        Map.of("deepseek", new AiProviderProperties.Provider(
            "DeepSeek",
            URI.create("https://example.invalid/v1"),
            "test-key",
            "deepseek-chat",
            true,
            Duration.ofSeconds(3),
            Map.of())),
        2);
    invoker = new StructuredOutputInvoker(
        gateway, new ObjectMapper(), properties, meterRegistry, new AiMetrics(meterRegistry));
    request = new AiRequest("deepseek", "Return JSON", "Give an answer", Answer.class);
  }

  @Test
  void invalidJsonFollowedByValidJsonTakesTwoAttempts() {
    when(gateway.generate(any()))
        .thenReturn(response("not-json " + SECRET_IN_INVALID_OUTPUT))
        .thenReturn(response("{\"answer\":\"forty-two\"}"));

    Answer result = invoker.invoke(request, Answer.class);

    assertThat(result.answer()).isEqualTo("forty-two");
    var requests = ArgumentCaptor.forClass(AiRequest.class);
    verify(gateway, times(2)).generate(requests.capture());
    AiRequest repairRequest = requests.getAllValues().get(1);
    assertThat(repairRequest.userPrompt()).contains("Malformed JSON");
    assertThat(repairRequest.userPrompt()).doesNotContain(SECRET_IN_INVALID_OUTPUT);
    assertThat(meterRegistry.get("ai.structured.output.attempts").counter().count()).isEqualTo(2);
    assertThat(meterRegistry.get("interview_pilot.ai.structured_retries").counter().count())
        .isEqualTo(1);
  }

  @Test
  void exhaustedInvalidResponsesThrowOnlyASanitizedParseReason() {
    when(gateway.generate(any())).thenReturn(response("invalid " + SECRET_IN_INVALID_OUTPUT));

    assertThatThrownBy(() -> invoker.invoke(request, Answer.class))
        .isInstanceOf(AiStructuredOutputException.class)
        .hasMessageContaining("Malformed JSON")
        .hasMessageNotContaining(SECRET_IN_INVALID_OUTPUT);
    verify(gateway, times(2)).generate(any());
    assertThat(meterRegistry.get("ai.structured.output.exhausted").counter().count()).isEqualTo(1);
  }

  @Test
  void validationFailuresAreNeverRetried() {
    when(gateway.generate(any())).thenThrow(new IllegalArgumentException("invalid request"));

    assertThatThrownBy(() -> invoker.invoke(request, Answer.class))
        .isInstanceOf(IllegalArgumentException.class);
    verify(gateway).generate(any());
  }

  @Test
  void jsonNullFollowedByAValidObjectTakesTwoAttempts() {
    when(gateway.generate(any()))
        .thenReturn(response("null"))
        .thenReturn(response("{\"answer\":\"forty-two\"}"));

    Answer result = invoker.invoke(request, Answer.class);

    assertThat(result).isEqualTo(new Answer("forty-two"));
    verify(gateway, times(2)).generate(any());
  }

  @Test
  void exhaustedJsonNullResponsesThrowASanitizedError() {
    when(gateway.generate(any())).thenReturn(response("null"));

    assertThatThrownBy(() -> invoker.invoke(request, Answer.class))
        .isInstanceOf(AiStructuredOutputException.class)
        .hasMessageContaining("JSON shape did not match")
        .hasMessageNotContaining("null");
    verify(gateway, times(2)).generate(any());
  }

  @Test
  void parsesTheJobRequirementsShapeRequestedDuringInterviewCreation() {
    when(gateway.generate(any())).thenReturn(response("""
        {"competencies":["Java","mysql","Redis"],"preferredSkills":[]}
        """));

    JobRequirements result = invoker.invoke(request, JobRequirements.class);

    assertThat(result.competencies()).containsExactly("Java", "mysql", "Redis");
    assertThat(result.preferredSkills()).isEmpty();
    verify(gateway).generate(any());
  }

  private AiResponse response(String content) {
    return new AiResponse(content, "deepseek-chat", Duration.ofMillis(10));
  }

  private record Answer(String answer) {}
}
