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

class AnswerEvaluationFailureTest {
  private final AnswerEvaluationHandler handler = mock(AnswerEvaluationHandler.class);
  private final TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
  private final AnswerEvaluationListener listener = new AnswerEvaluationListener(handler, retries);
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.ANSWER_EVALUATION,
      "answer-eval:" + UUID.randomUUID() + ":2");
  private final UUID requestId = UUID.randomUUID();
  private final Message source = new Message(new byte[0], new MessageProperties());

  AnswerEvaluationFailureTest() {
    when(handler.inspect(task)).thenReturn(new AnswerEvaluationHandler.Target(UUID.randomUUID(), false, 3, 0));
  }

  @Test void deadLetterUsesTheCommittedGenerationTokenAndAnswerIdentity() {
    when(handler.evaluate(task)).thenThrow(failure(new IllegalStateException("model failed")));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).routeFailure(task, source);
    order.verify(handler).markDead(task, 4, "owner", requestId);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }

  @Test void failedRetryPublicationKeepsTheDatabaseLease() {
    when(handler.evaluate(task)).thenThrow(failure(new IllegalStateException("model failed")));
    when(retries.routeFailure(task, source)).thenThrow(new AmqpException("broker unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
    verify(handler, never()).markDead(any(), anyInt(), any(), any());
  }

  @Test void confirmedRetryPrecedesLeaseRelease() {
    when(handler.evaluate(task)).thenThrow(failure(new IllegalStateException("model failed")));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.RETRY);
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).routeFailure(task, source);
    order.verify(handler).releaseForRetry(task, 4, "owner");
  }

  @Test void persistenceFailureAfterDeadLetterEscapesForRedelivery() {
    when(handler.evaluate(task)).thenThrow(failure(new IllegalStateException("model failed")));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 4, "owner", requestId)).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(IllegalStateException.class);
  }

  @Test void busyDeliveryDefersWithoutConsumingTheLastBusinessRetry() {
    when(handler.evaluate(task)).thenReturn(AnswerEvaluationHandler.Outcome.BUSY);
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void failedDeferralEscapes() {
    when(handler.evaluate(task)).thenReturn(AnswerEvaluationHandler.Outcome.BUSY);
    doThrow(new AmqpException("broker unavailable")).when(retries).defer(task, source);
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
  }

  @Test void inspectionFailureDefersWithoutInventingAnExecutionIdentity() {
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database unavailable"));
    listener.receive(task, source);
    verify(handler, never()).evaluate(any());
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void staleResultDoesNotPublishOrRelease() {
    when(handler.evaluate(task)).thenReturn(AnswerEvaluationHandler.Outcome.STALE);
    listener.receive(task, source);
    verifyNoInteractions(retries);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }

  @Test void optimisticConflictDefersBeforeReleasingTheMatchingExecution() {
    when(handler.evaluate(task)).thenThrow(failure(new OptimisticLockingFailureException("row changed")));
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).defer(task, source);
    order.verify(handler).releaseForRetry(task, 4, "owner");
    verify(retries, never()).routeFailure(any(), any());
  }

  private AnswerEvaluationRetryableException failure(Throwable cause) {
    return new AnswerEvaluationRetryableException(4, "owner", requestId, cause);
  }
}
