package interview.pilot.ai.provider;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.common.ratelimit.RateLimitedException;
import interview.pilot.common.ratelimit.RateLimitUnavailableException;

@Service
public class AiProviderService {
  private static final long DEFAULT_SETTING_ID = 1L;

  private final AiProviderProperties properties;
  private final AiSettingRepository settingRepository;
  private final AiProviderRegistry registry;

  public AiProviderService(
      AiProviderProperties properties,
      AiSettingRepository settingRepository,
      AiProviderRegistry registry) {
    this.properties = properties;
    this.settingRepository = settingRepository;
    this.registry = registry;
  }

  @Transactional(readOnly = true)
  public List<AiProviderDescriptor> list() {
    String defaultProvider = currentDefaultProviderId();
    return properties.providers().entrySet().stream()
        .map(entry -> descriptor(entry.getKey(), entry.getValue(), defaultProvider))
        .sorted(Comparator.comparing(AiProviderDescriptor::id))
        .toList();
  }

  @Transactional
  public AiProviderDescriptor switchDefault(String id) {
    AiProviderProperties.Provider provider = requireEnabledProvider(id);
    AiSettingEntity setting = settingRepository.findById(DEFAULT_SETTING_ID)
        .orElseThrow(() -> new IllegalStateException("Default AI provider setting is missing"));
    setting.setProviderId(id);
    settingRepository.save(setting);
    return descriptor(id, provider, id);
  }

  public ProviderTestResult test(String id) {
    requireEnabledProvider(id);
    long startedAt = System.nanoTime();
    try {
      AiProviderRegistry.Completion completion = registry.generate(
          id,
          "You are a connectivity checker.",
          "Reply with exactly OK.");
      if (completion.content() == null || !"OK".equalsIgnoreCase(completion.content().trim())) {
        return new ProviderTestResult(
            false,
            Duration.ofNanos(System.nanoTime() - startedAt),
            "Connection test failed");
      }
      return new ProviderTestResult(
          true,
          Duration.ofNanos(System.nanoTime() - startedAt),
          null);
    } catch (RateLimitedException | RateLimitUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      return new ProviderTestResult(
          false,
          Duration.ofNanos(System.nanoTime() - startedAt),
          "Connection test failed");
    }
  }

  @Transactional(readOnly = true)
  public String currentDefaultProviderId() {
    return settingRepository.findById(DEFAULT_SETTING_ID)
        .map(AiSettingEntity::getProviderId)
        .orElse(properties.defaultProvider());
  }

  /** Resolves one enabled provider and its model for immutable request snapshots. */
  @Transactional(readOnly = true)
  public AiProviderDescriptor resolveEnabled(String requestedId) {
    String id = requestedId == null || requestedId.isBlank()
        ? currentDefaultProviderId()
        : requestedId.trim();
    AiProviderProperties.Provider provider = requireEnabledProvider(id);
    return descriptor(id, provider, id);
  }

  private AiProviderProperties.Provider requireEnabledProvider(String id) {
    AiProviderProperties.Provider provider = properties.providers().get(id);
    if (provider == null) {
      throw new AiProviderException("Unknown AI provider: " + id);
    }
    if (!provider.enabled() || !provider.isComplete()) {
      throw new AiProviderException("AI provider is disabled or incomplete: " + id);
    }
    return provider;
  }

  private AiProviderDescriptor descriptor(
      String id,
      AiProviderProperties.Provider provider,
      String defaultProvider) {
    return new AiProviderDescriptor(
        id,
        provider.displayName(),
        provider.model(),
        provider.enabled() && provider.isComplete(),
        id.equals(defaultProvider));
  }

  public record ProviderTestResult(boolean success, Duration latency, String error) {}
}
