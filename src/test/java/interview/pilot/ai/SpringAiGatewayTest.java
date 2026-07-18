package interview.pilot.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.provider.AiProviderRegistry;
import interview.pilot.ai.provider.AiProviderService;

class SpringAiGatewayTest {
  @Test
  void preservesTheProviderFailureAsTheCause() {
    AiProviderRegistry registry = mock(AiProviderRegistry.class);
    AiProviderService providerService = mock(AiProviderService.class);
    RuntimeException providerFailure = new RuntimeException("provider detail");
    when(registry.generate("deepseek", "deepseek-chat", "system", "user"))
        .thenThrow(providerFailure);
    SpringAiGateway gateway = new SpringAiGateway(registry, providerService);

    assertThatThrownBy(() -> gateway.generate(new AiRequest(
        "deepseek", "deepseek-chat", "system", "user", String.class)))
        .isInstanceOf(AiGatewayException.class)
        .hasMessage("AI provider call failed: deepseek")
        .hasCause(providerFailure);
  }
}
