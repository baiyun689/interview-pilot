package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.*;

class ResumeAnalysisListenerTest {
  private final ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
  private final TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
  private final ResumeAnalysisListener listener = new ResumeAnalysisListener(handler, retries);
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42");
  private final Message source = new Message(new byte[0], new MessageProperties());

  ResumeAnalysisListenerTest() {
    when(handler.inspect(task)).thenReturn(new ResumeAnalysisHandler.ResumeAnalysisTarget(42L, false));
  }

  @Test void activeDatabaseOwnerDefersWithoutSpendingBusinessRetries() {
    when(handler.handle(task)).thenReturn(ResumeAnalysisHandler.Outcome.BUSY);
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
    verify(handler, never()).markDead(any(), anyInt(), any());
  }

  @Test void failedDeferralEscapesForBrokerRedelivery() {
    when(handler.handle(task)).thenReturn(ResumeAnalysisHandler.Outcome.BUSY);
    doThrow(new AmqpException("broker unavailable")).when(retries).defer(task, source);
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
  }

  @Test void retryPublicationFailureMustKeepTheDatabaseLease() {
    when(handler.handle(task)).thenThrow(new ResumeAnalysisRetryableException(2, "owner"));
    when(retries.routeFailure(task, source)).thenThrow(new AmqpException("broker unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
    verify(handler, never()).markDead(any(), anyInt(), any());
  }

  @Test void confirmedRetryReleasesOnlyTheFailingExecution() {
    when(handler.handle(task)).thenThrow(new ResumeAnalysisRetryableException(2, "owner"));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.RETRY);
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).routeFailure(task, source);
    order.verify(handler).releaseForRetry(task, 2, "owner");
  }

  @Test void deadLetterUsesTheActualExecutionTokenAndDatabaseFailureEscapes() {
    when(handler.handle(task)).thenThrow(new ResumeAnalysisRetryableException(2, "owner"));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 2, "owner")).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(IllegalStateException.class);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }

  @Test void aFailureWithoutExecutionIdentityIsDeferred() {
    when(handler.handle(task)).thenThrow(new IllegalStateException("begin transaction failed"));
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
  }

  @Test void staleExecutionDoesNotPublishAnotherRetry() {
    when(handler.handle(task)).thenReturn(ResumeAnalysisHandler.Outcome.STALE);
    listener.receive(task, source);
    verifyNoInteractions(retries);
  }

  @Test void untrustedTaskTypeCannotRouteIntoAnotherPipeline() {
    var untrusted = new TaskMessage(task.taskId(), AsyncTaskType.INTERVIEW_EVALUATION, task.bizKey());
    when(handler.inspect(untrusted)).thenThrow(new IllegalArgumentException("wrong type"));
    when(retries.routeFailure(task, source)).thenThrow(new AmqpException("publisher unavailable"));
    assertThatThrownBy(() -> listener.receive(untrusted, source)).isInstanceOf(AmqpException.class);
    verify(retries).routeFailure(task, source);
  }
}
