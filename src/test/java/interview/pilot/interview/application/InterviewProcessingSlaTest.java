package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import interview.pilot.ai.provider.AiProviderProperties;

class InterviewProcessingSlaTest {
  @Test
  void coversTwoSequentialStructuredCallsAndKeepsSseBeyondProcessing() {
    var provider = new AiProviderProperties.Provider(
        "P", URI.create("https://example.invalid"), "key", "model", true,
        Duration.ofSeconds(30));
    var sla = new InterviewProcessingSla(
        new AiProviderProperties("p", Map.of("p", provider), 2),
        Duration.ofSeconds(15), Duration.ofSeconds(15));

    assertThat(sla.processingTimeout()).isEqualTo(Duration.ofSeconds(255));
    assertThat(sla.sseTimeout()).isEqualTo(Duration.ofSeconds(270));
    assertThat(sla.sseTimeout()).isGreaterThan(sla.processingTimeout());
  }

  @Test
  void ignoresDisabledProvidersAndRejectsUnsafeConfigurationAtConstruction() {
    var disabled = new AiProviderProperties.Provider(
        "P", URI.create("https://example.invalid"), "key", "model", false,
        Duration.ofHours(10));
    var safe = new InterviewProcessingSla(
        new AiProviderProperties("p", Map.of("p", disabled), 1),
        Duration.ofSeconds(15), Duration.ofSeconds(15));
    assertThat(safe.processingTimeout()).isEqualTo(Duration.ofSeconds(15));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InterviewProcessingSla(
            new AiProviderProperties("", Map.of(), 0), Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InterviewProcessingSla(
            new AiProviderProperties("", Map.of(), 1), Duration.ofSeconds(-1), Duration.ZERO))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InterviewProcessingSla(
            new AiProviderProperties("p", Map.of("p", new AiProviderProperties.Provider(
                "P", URI.create("https://example.invalid"), "key", "model", true,
                Duration.ofHours(10))), 2), Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Interview processing SLA is outside safe bounds");
  }
}
