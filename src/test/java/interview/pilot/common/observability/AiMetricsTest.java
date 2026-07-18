package interview.pilot.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import interview.pilot.async.domain.AsyncTaskType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiMetricsTest {
  @Test
  void recordsTheSevenExactBoundedMeters() {
    var registry = new SimpleMeterRegistry();
    var metrics = new AiMetrics(registry);

    metrics.aiCall("deepseek", "success", Duration.ofMillis(12));
    metrics.structuredRetry("deepseek");
    metrics.taskCompleted(AsyncTaskType.RESUME_ANALYSIS);
    metrics.taskFailed(AsyncTaskType.INTERVIEW_EVALUATION, "dead");
    metrics.answerDuplicate("replay");
    metrics.optimisticLockConflict();

    assertThat(registry.get("interview_pilot.ai.calls").counter().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.ai.latency").timer().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.ai.structured_retries").counter().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.tasks.completed").counter().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.tasks.failed").counter().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.answers.duplicates").counter().count()).isEqualTo(1);
    assertThat(registry.get("interview_pilot.optimistic_lock.conflicts").counter().count())
        .isEqualTo(1);
  }
}
