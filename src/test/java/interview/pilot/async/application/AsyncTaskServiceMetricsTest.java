package interview.pilot.async.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;

class AsyncTaskServiceMetricsTest {
  @Test
  void explicitVersionFenceCountsExactlyOneOptimisticConflict() {
    UUID taskId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    var captured = task(7L, taskId, sessionId, 3L);
    var changed = task(7L, taskId, sessionId, 4L);
    var tasks = mock(AsyncTaskRepository.class);
    when(tasks.findByTaskId(taskId)).thenReturn(Optional.of(captured));
    when(tasks.findById(7L)).thenReturn(Optional.of(changed));
    var claims = mock(ProcessingClaim.class);
    when(claims.clearTerminal("interview-report:" + sessionId))
        .thenReturn(ProcessingClaim.ClearResult.ABSENT);
    var transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    var metrics = mock(AiMetrics.class);
    var service = new AsyncTaskService(
        tasks, mock(ResumeRepository.class), mock(InterviewSessionRepository.class),
        claims, transactionManager, metrics);

    assertThatThrownBy(() -> service.retry(taskId, UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> org.assertj.core.api.Assertions.assertThat(error.code())
                .isEqualTo("TASK_RETRY_CONFLICT"));

    verify(metrics).optimisticLockConflict();
  }

  private AsyncTaskEntity task(long id, UUID taskId, UUID sessionId, long version) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, "{}");
    task.setId(id);
    task.setTaskId(taskId);
    task.setStatus(AsyncTaskStatus.FAILED);
    task.setVersion(version);
    return task;
  }
}
