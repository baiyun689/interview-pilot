package interview.pilot.interview.preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClasspathInterviewPresetCatalogTest {
  private final ClasspathInterviewPresetCatalog catalog =
      new ClasspathInterviewPresetCatalog();

  @Test
  void loadsTheServerOwnedJavaBackendJdWithAStableContentVersion() {
    InterviewPreset preset = catalog.require("java-backend");

    assertThat(preset.id()).isEqualTo("java-backend");
    assertThat(preset.jobTitle()).isEqualTo("Java 后端开发工程师");
    assertThat(preset.jobDescription()).contains("Java", "Spring", "MySQL");
    assertThat(preset.version()).matches("[0-9a-f]{64}");
    assertThat(catalog.list()).containsExactly(preset);
  }

  @Test
  void rejectsUnknownPresetIds() {
    assertThatThrownBy(() -> catalog.require("missing"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
  }
}
