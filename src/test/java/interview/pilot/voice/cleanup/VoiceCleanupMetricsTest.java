package interview.pilot.voice.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class VoiceCleanupMetricsTest {

  @Test
  void countsDeletesAndDeferralsPerKind() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceCleanupMetrics(meters);

    metrics.deleted("receiving");
    metrics.deleted("receiving");
    metrics.deleted("discarded");
    metrics.deferred("orphan");
    metrics.deleted("bad tag!@#");

    assertThat(meters.counter("interview_pilot.voice.cleanup.total",
        "result", "deleted", "kind", "receiving").count()).isEqualTo(2);
    assertThat(meters.counter("interview_pilot.voice.cleanup.total",
        "result", "deleted", "kind", "discarded").count()).isEqualTo(1);
    assertThat(meters.counter("interview_pilot.voice.cleanup.total",
        "result", "deferred", "kind", "orphan").count()).isEqualTo(1);
    // tags that would pollute the dashboard are bounded to "other"
    assertThat(meters.counter("interview_pilot.voice.cleanup.total",
        "result", "deleted", "kind", "other").count()).isEqualTo(1);
  }
}
