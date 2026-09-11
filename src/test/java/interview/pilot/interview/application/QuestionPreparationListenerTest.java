package interview.pilot.interview.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.OptimisticLockingFailureException;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

class QuestionPreparationListenerTest {
  @Test
  void duplicateDeliveryDoesNotConsumeTheActiveOwnersRetryBudget() {
    var handler = mock(QuestionPreparationHandler.class);
    var claims = mock(ProcessingClaim.class);
    var retries = mock(TaskRetryPolicy.class);
    var listener = new QuestionPreparationListener(handler, claims, retries);
    var task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        "interview:" + UUID.randomUUID());
    var source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(new QuestionPreparationHandler.Target(
        UUID.randomUUID(), 1L, null, false));
    when(claims.acquire(anyString(), any())).thenReturn(Optional.empty());

    listener.receive(task, source);

    verify(handler, never()).prepare(any());
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test
  void optimisticLockConflictDuringInspectionDoesNotConsumeRetryBudget() {
    var handler = mock(QuestionPreparationHandler.class);
    var claims = mock(ProcessingClaim.class);
    var retries = mock(TaskRetryPolicy.class);
    var listener = new QuestionPreparationListener(handler, claims, retries);
    var task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        "interview:" + UUID.randomUUID());
    var source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenThrow(new OptimisticLockingFailureException("row changed"));

    listener.receive(task, source);

    verify(claims, never()).acquire(anyString(), any());
    verify(retries, never()).routeFailure(any(), any());
  }
}
