package interview.pilot.voice.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class VoiceCleanupPropertiesTest {

  @Test
  void acceptsPositiveTuning() {
    var properties = new VoiceCleanupProperties(
        25, Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofMinutes(30));
    assertThat(properties.batchSize()).isEqualTo(25);
    assertThat(properties.stuckTaskThreshold()).isEqualTo(Duration.ofMinutes(30));
    assertThat(properties.orphanGrace()).isEqualTo(Duration.ofHours(24));
    assertThat(properties.claimTtl()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void rejectsNonPositiveBatchSize() {
    assertThatThrownBy(() -> new VoiceCleanupProperties(
        0, Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch size");
  }

  @Test
  void rejectsZeroOrNegativeDurations() {
    assertThatThrownBy(() -> new VoiceCleanupProperties(
        50, Duration.ZERO, Duration.ofHours(24), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stuck task threshold");
    assertThatThrownBy(() -> new VoiceCleanupProperties(
        50, Duration.ofMinutes(30), Duration.ofSeconds(-1), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orphan grace");
    assertThatThrownBy(() -> new VoiceCleanupProperties(
        50, Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofMinutes(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("claim ttl");
  }
}
