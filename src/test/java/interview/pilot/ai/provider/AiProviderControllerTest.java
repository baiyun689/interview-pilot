package interview.pilot.ai.provider;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiProviderControllerTest {
  private static final String SECRET = "sk-controller-secret";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    var properties = new AiProviderProperties(
        "disabled",
        Map.of("disabled", new AiProviderProperties.Provider(
            "Disabled Provider",
            URI.create("https://example.invalid"),
            SECRET,
            "disabled-model",
            false,
            Duration.ofSeconds(3),
            Map.of())),
        2);
    var service = new AiProviderService(
        properties,
        mock(AiSettingRepository.class),
        mock(AiProviderRegistry.class));
    mockMvc = MockMvcBuilders.standaloneSetup(new AiProviderController(service)).build();
  }

  @Test
  void unknownProviderConnectivityTestReturnsSanitizedBadRequest() throws Exception {
    mockMvc.perform(post("/api/ai/providers/unknown/test"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(SECRET))));
  }

  @Test
  void disabledDefaultProviderReturnsSanitizedBadRequest() throws Exception {
    mockMvc.perform(put("/api/ai/providers/default")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"providerId\":\"disabled\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(SECRET))));
  }
}
