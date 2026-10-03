package interview.pilot.interview.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;

class AnswerRecoverySchedulerTest {
  @Test void oneBrokenAttemptDoesNotBlockLaterAnswersOrPinTheNextScan() {
    var attempts = mock(AnswerAttemptRepository.class);
    var answers = mock(FixedAnswerService.class);
    var sla = mock(InterviewProcessingSla.class);
    when(sla.sseTimeout()).thenReturn(Duration.ofMinutes(5));
    var first = candidate(1L);
    var second = candidate(2L);
    when(attempts.findExpired(any(), eq(0L), any())).thenReturn(List.of(first, second));
    when(attempts.findExpired(any(), eq(2L), any())).thenReturn(List.of());
    when(answers.recoverExpired(eq(1L), eq(7L), any())).thenThrow(new IllegalStateException("bad snapshot"));
    var scheduler = new AnswerRecoveryScheduler(attempts, answers, sla);
    scheduler.recover();
    verify(answers).recoverExpired(eq(2L), eq(7L), any());
    scheduler.recover();
    verify(attempts).findExpired(any(), eq(2L), any());
    scheduler.recover();
    verify(attempts, times(2)).findExpired(any(), eq(0L), any());
  }

  private AnswerAttemptRepository.RecoveryCandidate candidate(Long id) {
    var candidate = mock(AnswerAttemptRepository.RecoveryCandidate.class);
    when(candidate.getId()).thenReturn(id);
    when(candidate.getSessionId()).thenReturn(7L);
    return candidate;
  }
}
