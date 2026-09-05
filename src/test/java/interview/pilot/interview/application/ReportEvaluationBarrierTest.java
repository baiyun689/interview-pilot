package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.EvalStatus;

class ReportEvaluationBarrierTest {
  private final ReportEvaluationBarrier barrier = new ReportEvaluationBarrier();

  @Test
  void proceedsWhenNoFormalTurnIsPending() {
    List<EvalStatus> statuses = List.of(EvalStatus.OK, EvalStatus.GENERAL_FALLBACK, EvalStatus.FAILED);
    assertThat(barrier.hasPendingEvaluation(statuses)).isFalse();
    assertThat(barrier.shouldAwait(statuses, 0, 3)).isFalse();
  }

  @Test
  void awaitsAcrossFirstDeliveryAndEarlyRetries() {
    List<EvalStatus> statuses = List.of(EvalStatus.OK, EvalStatus.PENDING);
    assertThat(barrier.hasPendingEvaluation(statuses)).isTrue();
    // First delivery and the 5s/30s retries keep waiting for the per-turn evaluation.
    assertThat(barrier.shouldAwait(statuses, 0, 3)).isTrue();
    assertThat(barrier.shouldAwait(statuses, 1, 3)).isTrue();
    assertThat(barrier.shouldAwait(statuses, 2, 3)).isTrue();
  }

  @Test
  void proceedsOnTheLastRetryEvenIfPendingRemainsSoTheReportNeverDeadLetters() {
    List<EvalStatus> statuses = List.of(EvalStatus.PENDING, EvalStatus.OK);
    // The 120s retry is the last consumption attempt (completedRetries == max): proceed and
    // fall back to raw text for the still-pending turn instead of dead-lettering the report.
    assertThat(barrier.shouldAwait(statuses, 3, 3)).isFalse();
  }

  @Test
  void aSmallerWindowStopsWaitingEarlier() {
    List<EvalStatus> statuses = List.of(EvalStatus.PENDING);
    assertThat(barrier.shouldAwait(statuses, 0, 1)).isTrue();
    assertThat(barrier.shouldAwait(statuses, 1, 1)).isFalse();
  }
}
