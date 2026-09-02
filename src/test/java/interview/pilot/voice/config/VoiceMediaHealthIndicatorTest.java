package interview.pilot.voice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class VoiceMediaHealthIndicatorTest {

  @TempDir
  Path tempDir;

  @Test
  void reportsUpWhenTheVoiceRootIsWritable() {
    var indicator = indicator(tempDir);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void reportsDownWithRootDetailWhenTheVoiceRootIsNotWritable() throws Exception {
    // A regular file in place of the directory: createDirectories fails portably
    // (FileAlreadyExistsException) on every platform, including Windows.
    Path fileAsRoot = Files.createTempFile(tempDir, "voice-health-", ".file");
    var indicator = indicator(fileAsRoot);

    Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("voiceFilesRoot");
    assertThat(health.getDetails().get("voiceFilesRoot"))
        .isEqualTo(fileAsRoot.toString());
  }

  @Test
  void leavesNoProbeFileBehind() throws Exception {
    var indicator = indicator(tempDir);

    indicator.health();
    try (var children = Files.list(tempDir)) {
      assertThat(children).isEmpty();
    }
  }

  private static VoiceMediaHealthIndicator indicator(Path root) {
    var voice = new VoiceProperties(
        true, root, 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr("dashscope", "https://dashscope.aliyuncs.com/api/v1",
            "sk-test", "fun-asr-flash-2026-06-15", Duration.ofSeconds(60)),
        new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang",
            Duration.ofSeconds(30)));
    return new VoiceMediaHealthIndicator(voice);
  }
}
