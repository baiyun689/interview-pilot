package interview.pilot.voice.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.voice.config.VoiceProperties;

class VoiceCapabilitiesControllerTest {
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new VoiceCapabilitiesController(properties(true))).build();
  }

  @Test
  void reportsEnabledCapabilitiesWithTheFiveAcceptedMimeTypes() throws Exception {
    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.supportedMimeTypes", contains(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/wav", "audio/mpeg")))
        .andExpect(jsonPath("$.maxRecordingSeconds").value(300))
        .andExpect(jsonPath("$.maxUploadBytes").value(8_388_608))
        .andExpect(jsonPath("$.ttsEnabled").value(true));
  }

  @Test
  void derivesMaxRecordingSecondsFromTheConfiguredDuration() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(new VoiceCapabilitiesController(
        new VoiceProperties(
            true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(10), Duration.ofDays(7),
            asr(), new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang",
                Duration.ofSeconds(30))))).build();

    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(jsonPath("$.maxRecordingSeconds").value(600));
  }

  @Test
  void reportsDisabledWhenVoiceIsTurnedOff() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(new VoiceCapabilitiesController(
        new VoiceProperties(
            false, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
            asr(), new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang",
                Duration.ofSeconds(30))))).build();

    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.ttsEnabled").value(false));
  }

  @Test
  void reportsTtsDisabledWhenTtsIsUnconfigured() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(new VoiceCapabilitiesController(
        new VoiceProperties(
            true, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
            asr(), null))).build();

    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.ttsEnabled").value(false));
  }

  @Test
  void clampsGarbageLimitsToZeroWhenVoiceIsDisabled() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(new VoiceCapabilitiesController(
        new VoiceProperties(
            false, null, -5, Duration.ofMinutes(-5), Duration.ZERO, null, null))).build();

    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.maxRecordingSeconds").value(0))
        .andExpect(jsonPath("$.maxUploadBytes").value(0));
  }

  @Test
  void neverExposesCredentialsOrProviderAddresses() throws Exception {
    mockMvc.perform(get("/api/voice/capabilities"))
        .andExpect(jsonPath("$.*").value(org.hamcrest.Matchers
            .everyItem(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("sk-")))));
    String body = mockMvc.perform(get("/api/voice/capabilities"))
        .andReturn().getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body)
        .doesNotContain("api-key", "workspace", "base-url", "dashscope.aliyuncs.com");
  }

  private static VoiceProperties properties(boolean enabled) {
    return new VoiceProperties(
        enabled, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        asr(), new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang",
            Duration.ofSeconds(30)));
  }

  private static VoiceProperties.Asr asr() {
    return new VoiceProperties.Asr(
        "dashscope", "https://dashscope.aliyuncs.com/api/v1", "", "sk-test",
        "fun-asr-flash-2026-06-15", Duration.ofSeconds(60));
  }
}
