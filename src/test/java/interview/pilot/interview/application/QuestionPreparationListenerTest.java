package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.*;

class QuestionPreparationListenerTest {
  private final QuestionPreparationHandler handler = mock(QuestionPreparationHandler.class);
  private final TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
  private final QuestionPreparationListener listener = new QuestionPreparationListener(handler, retries);
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
      "interview:" + UUID.randomUUID());
  private final Message source = new Message(new byte[0], new MessageProperties());

  QuestionPreparationListenerTest() {
    when(handler.inspect(task)).thenReturn(new QuestionPreparationHandler.Target(UUID.randomUUID(), 1L, null, false));
  }

  @Test void databaseBusyDefersWithoutSpendingBusinessRetries() {
    when(handler.prepare(task)).thenReturn(QuestionPreparationHandler.Outcome.BUSY);
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void failedDeferralEscapesForBrokerRedelivery() {
    when(handler.prepare(task)).thenReturn(QuestionPreparationHandler.Outcome.BUSY);
    doThrow(new AmqpException("broker unavailable")).when(retries).defer(task, source);
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
  }

  @Test void inspectionFailureDoesNotSpendRetriesOrExecuteTheModel() {
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database unavailable"));
    listener.receive(task, source);
    verify(handler, never()).prepare(any());
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void retryPublicationFailurePreservesTheExecutionLease() {
    when(handler.prepare(task)).thenThrow(failure(new IllegalStateException("model unavailable")));
    when(retries.routeFailure(task, source)).thenThrow(new AmqpException("broker unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
    verify(handler, never()).markDead(any(), anyInt(), any());
  }

  @Test void confirmedRetryIsPublishedBeforeReleasingTheMatchingToken() {
    when(handler.prepare(task)).thenThrow(failure(new IllegalStateException("model unavailable")));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.RETRY);
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).routeFailure(task, source);
    order.verify(handler).releaseForRetry(task, 2, "owner");
  }

  @Test void invalidDeckFailsOnlyItsExecutionWithoutAnotherRetry() {
    when(handler.prepare(task)).thenThrow(failure(new InvalidQuestionDeckException("invalid")));
    listener.receive(task, source);
    verify(handler).markInvalid(task, 2, "owner");
    verifyNoInteractions(retries);
  }

  @Test void terminalPersistenceFailureEscapesInsteadOfAcknowledging() {
    when(handler.prepare(task)).thenThrow(failure(new IllegalStateException("model unavailable")));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 2, "owner")).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(IllegalStateException.class);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }

  @Test void optimisticConflictDefersBeforeReleasingItsExecution() {
    when(handler.prepare(task)).thenThrow(failure(new OptimisticLockingFailureException("row changed")));
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).defer(task, source);
    order.verify(handler).releaseForRetry(task, 2, "owner");
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void staleExecutionDoesNotPublishOrReleaseAnything() {
    when(handler.prepare(task)).thenReturn(QuestionPreparationHandler.Outcome.STALE);
    listener.receive(task, source);
    verifyNoInteractions(retries);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }

  private QuestionPreparationExecutionException failure(RuntimeException cause) {
    return new QuestionPreparationExecutionException(2, "owner", cause);
  }
}
