package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.infrastructure.LegacyUserAccountIdProvider;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;

class InterviewCompletionServiceTest {
  @Test
  void createsLegacyOwnedReportTaskUntilInterviewOwnershipIsPropagated() {
    AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
    LegacyUserAccountIdProvider owners = mock(LegacyUserAccountIdProvider.class);
    when(owners.currentUserAccountId()).thenReturn(73L);
    InterviewSessionEntity session = evaluatingSession();
    when(tasks.findByTaskTypeAndBizKey(any(), any())).thenReturn(Optional.empty());
    when(tasks.save(any(AsyncTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AsyncTaskEntity task = new InterviewCompletionService(tasks, owners).ensureReportTask(session);

    assertThat(task.getUserAccountId()).isEqualTo(73L);
  }

  private static InterviewSessionEntity evaluatingSession() {
    InterviewSessionEntity session = InterviewSessionEntity.create(
        11L, 12L, Difficulty.MEDIUM, 1, "provider", "model", "{}");
    session.start();
    session.beginEvaluation();
    return session;
  }
}
