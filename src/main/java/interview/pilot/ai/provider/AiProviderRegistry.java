package interview.pilot.ai.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

import interview.pilot.common.observability.AiMetrics;
import interview.pilot.common.ratelimit.LlmConcurrencyAdmission;

@Component
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiProviderRegistry {
  private final Map<String, RegisteredProvider> providers;
  private final LlmConcurrencyAdmission admission;
  private final AiMetrics metrics;

  @Autowired
  public AiProviderRegistry(
      AiProviderProperties properties,
      LlmConcurrencyAdmission admission,
      AiMetrics metrics) {
    Map<String, RegisteredProvider> configuredProviders = new LinkedHashMap<>();
    properties.providers().forEach((id, provider) -> {
      if (provider.enabled() && provider.isComplete()) {
        configuredProviders.put(id, createProvider(provider));
      }
    });
    this.providers = Map.copyOf(configuredProviders);
    this.admission = admission;
    this.metrics = metrics;
  }

  public Completion generate(String providerId, String systemPrompt, String userPrompt) {
    return generate(providerId, null, systemPrompt, userPrompt);
  }

  public Completion generate(
      String providerId, String expectedModel, String systemPrompt, String userPrompt) {
    RegisteredProvider provider = providers.get(providerId);
    if (provider == null) {
      throw new AiProviderException("Unknown, disabled, or incomplete AI provider: " + providerId);
    }
    if (expectedModel != null && !expectedModel.isBlank()
        && !provider.model().equals(expectedModel)) {
      throw new AiProviderException("The session model snapshot is no longer available");
    }
    String token = admission.acquire();
    long started = System.nanoTime();
    String outcome = "failure";
    try {
      var prompt = new Prompt(
          new SystemMessage(systemPrompt),
          new UserMessage(userPrompt));
      String content = provider.chatModel().call(prompt)
          .getResult()
          .getOutput()
          .getText();
      outcome = "success";
      return new Completion(content, provider.model());
    } finally {
      try {
        metrics.aiCall(providerId, outcome, Duration.ofNanos(System.nanoTime() - started));
      } catch (RuntimeException ignored) {
        // Observability must not change provider results or delay lease release.
      } finally {
        admission.release(token);
      }
    }
  }

  private RegisteredProvider createProvider(AiProviderProperties.Provider provider) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(provider.timeout());
    requestFactory.setReadTimeout(provider.timeout());
    var restClientBuilder = RestClient.builder().requestFactory(requestFactory);

    OpenAiApi api = OpenAiApi.builder()
        .baseUrl(provider.baseUrl().toString())
        .apiKey(provider.apiKey())
        .restClientBuilder(restClientBuilder)
        .build();
    var optionsBuilder = OpenAiChatOptions.builder()
        .model(provider.model());
    if (!provider.extraBody().isEmpty()) {
      optionsBuilder.extraBody(provider.extraBody());
    }
    OpenAiChatOptions options = optionsBuilder.build();
    OpenAiChatModel model = OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(options)
        .build();
    return new RegisteredProvider(model, provider.model());
  }

  public record Completion(String content, String model) {}

  private record RegisteredProvider(OpenAiChatModel chatModel, String model) {}

}
