package interview.pilot.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import interview.pilot.common.ratelimit.RateLimitedException;
import interview.pilot.common.ratelimit.RateLimitUnavailableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AiProviderServiceTest {
  private static final String SECRET = "sk-test-secret";

  private AiSettingRepository settingRepository;
  private AiProviderRegistry registry;
  private AiProviderService service;
  private AiSettingEntity setting;
  private AiProviderProperties properties;

  @BeforeEach
  void setUp() {
    settingRepository = mock(AiSettingRepository.class);
    registry = mock(AiProviderRegistry.class);
    setting = new AiSettingEntity();
    setting.setProviderId("dashscope");
    when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
    when(settingRepository.save(setting)).thenReturn(setting);

    properties = new AiProviderProperties(
        "dashscope",
        Map.of(
            "dashscope", provider("DashScope", "qwen-plus", true),
            "deepseek", provider("DeepSeek", "deepseek-chat", true),
            "blank-name", provider(" ", "blank-name-model", true),
            "disabled", provider("Disabled", "disabled-model", false)),
        2);
    service = new AiProviderService(properties, settingRepository, registry);
  }

  @Test
  void switchesOnlyTheDefaultProviderId() {
    service.switchDefault("deepseek");

    assertThat(setting.getProviderId()).isEqualTo("deepseek");
  }

  @Test
  void descriptorsNeverExposeApiKeys() throws Exception {
    String json = new ObjectMapper().writeValueAsString(service.list());

    assertThat(json).doesNotContain(SECRET);
    assertThat(json).doesNotContainIgnoringCase("apiKey");
  }

  @Test
  void providerConfigurationToStringNeverExposesApiKeys() {
    assertThat(properties.toString()).doesNotContain(SECRET);
    assertThat(properties.providers().get("dashscope").toString()).doesNotContain(SECRET);
  }

  @Test
  void rejectsUnknownDefaultProvider() {
    assertThatThrownBy(() -> service.switchDefault("unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown AI provider")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void rejectsDisabledDefaultProvider() {
    assertThatThrownBy(() -> service.switchDefault("disabled"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disabled")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void rejectsEnabledProviderWithBlankDisplayName() {
    assertThatThrownBy(() -> service.switchDefault("blank-name"))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("incomplete")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void testsAProviderWithAFixedShortPrompt() {
    when(registry.generate(
        "deepseek", "You are a connectivity checker.", "Reply with exactly OK."))
        .thenReturn(new AiProviderRegistry.Completion("OK", "deepseek-chat"));

    AiProviderService.ProviderTestResult result = service.test("deepseek");

    assertThat(result.success()).isTrue();
    assertThat(result.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
    assertThat(result.error()).isNull();
    verify(registry).generate(
        "deepseek", "You are a connectivity checker.", "Reply with exactly OK.");
  }

  @Test
  void providerTestFailureReturnsOnlyASanitizedError() {
    when(registry.generate(
        "deepseek", "You are a connectivity checker.", "Reply with exactly OK."))
        .thenThrow(new RuntimeException("response contained " + SECRET));

    AiProviderService.ProviderTestResult result = service.test("deepseek");

    assertThat(result.success()).isFalse();
    assertThat(result.error()).isEqualTo("Connection test failed");
    assertThat(result.toString()).doesNotContain(SECRET);
  }

  @Test
  void providerTestRejectsAResponseOtherThanOk() {
    when(registry.generate(
        "deepseek", "You are a connectivity checker.", "Reply with exactly OK."))
        .thenReturn(new AiProviderRegistry.Completion(
            "Authentication Fails (governor)", "deepseek-chat"));

    AiProviderService.ProviderTestResult result = service.test("deepseek");

    assertThat(result.success()).isFalse();
    assertThat(result.error()).isEqualTo("Connection test failed");
    assertThat(result.toString()).doesNotContain("Authentication Fails");
  }

  @Test
  void providerTestDoesNotSwallowAdmissionFailures() {
    when(registry.generate(
        "deepseek", "You are a connectivity checker.", "Reply with exactly OK."))
        .thenThrow(new RateLimitedException())
        .thenThrow(new RateLimitUnavailableException());

    assertThatThrownBy(() -> service.test("deepseek"))
        .isInstanceOf(RateLimitedException.class);
    assertThatThrownBy(() -> service.test("deepseek"))
        .isInstanceOf(RateLimitUnavailableException.class);
  }

  @Test
  void resolvesTheCurrentDefaultWithItsConfiguredModelAsOneSnapshot() {
    AiProviderDescriptor resolved = service.resolveEnabled(null);

    assertThat(resolved.id()).isEqualTo("dashscope");
    assertThat(resolved.model()).isEqualTo("qwen-plus");
    assertThat(resolved.enabled()).isTrue();
  }

  @Test
  void trimsAndResolvesAnExplicitProviderWithoutReadingAnotherDefault() {
    AiProviderDescriptor resolved = service.resolveEnabled(" deepseek ");

    assertThat(resolved.id()).isEqualTo("deepseek");
    assertThat(resolved.model()).isEqualTo("deepseek-chat");
  }

  private AiProviderProperties.Provider provider(String displayName, String model, boolean enabled) {
    return new AiProviderProperties.Provider(
        displayName,
        URI.create("https://example.invalid/v1"),
        SECRET,
        model,
        enabled,
        Duration.ofSeconds(3));
  }
}
