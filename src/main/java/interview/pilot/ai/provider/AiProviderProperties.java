package interview.pilot.ai.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai")
public record AiProviderProperties(
    String defaultProvider,
    Map<String, Provider> providers,
    int structuredMaxAttempts) {

  public AiProviderProperties {
    providers = providers == null ? Map.of() : Map.copyOf(providers);
  }

  @Override
  public String toString() {
    return "AiProviderProperties[defaultProvider=" + defaultProvider
        + ", providers=" + providers.keySet()
        + ", structuredMaxAttempts=" + structuredMaxAttempts + "]";
  }

  public record Provider(
      String displayName,
      URI baseUrl,
      String apiKey,
      String model,
      boolean enabled,
      Duration timeout,
      Map<String, Object> extraBody) {

    public Provider {
      extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }

    public boolean isComplete() {
      return hasText(displayName)
          && baseUrl != null
          && hasText(apiKey)
          && hasText(model)
          && timeout != null
          && !timeout.isNegative()
          && !timeout.isZero();
    }

    @Override
    public String toString() {
      return "Provider[displayName=" + displayName
          + ", baseUrl=" + baseUrl
          + ", apiKey=<redacted>"
          + ", model=" + model
          + ", enabled=" + enabled
          + ", timeout=" + timeout + "]";
    }

    private static boolean hasText(String value) {
      return value != null && !value.isBlank();
    }
  }
}
