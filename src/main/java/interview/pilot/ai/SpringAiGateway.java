package interview.pilot.ai;

import java.time.Duration;

import org.springframework.stereotype.Component;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.model.AiResponse;
import interview.pilot.ai.provider.AiProviderRegistry;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.common.exception.BusinessException;

@Component
public class SpringAiGateway implements AiGateway {
  private final AiProviderRegistry registry;
  private final AiProviderService providerService;

  public SpringAiGateway(AiProviderRegistry registry, AiProviderService providerService) {
    this.registry = registry;
    this.providerService = providerService;
  }

  @Override
  public AiResponse generate(AiRequest request) {
    validate(request);
    String providerId = hasText(request.providerId())
        ? request.providerId()
        : providerService.currentDefaultProviderId();
    long startedAt = System.nanoTime();
    try {
      AiProviderRegistry.Completion completion = registry.generate(
          providerId, request.expectedModel(), request.systemPrompt(), request.userPrompt());
      return new AiResponse(
          completion.content(),
          completion.model(),
          Duration.ofNanos(System.nanoTime() - startedAt));
    } catch (IllegalArgumentException | BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AiGatewayException("AI provider call failed: " + providerId, exception);
    }
  }

  private void validate(AiRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("AI request is required");
    }
    if (!hasText(request.systemPrompt()) || !hasText(request.userPrompt())) {
      throw new IllegalArgumentException("AI prompts must not be blank");
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
