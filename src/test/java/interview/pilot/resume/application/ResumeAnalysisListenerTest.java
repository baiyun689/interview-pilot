package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

class ResumeAnalysisListenerTest {
  @Test
  void untrustedTaskTypeCannotRouteAResumeQueueFailureIntoAnotherPipeline() {
    ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new ResumeAnalysisListener(
        handler, mock(ProcessingClaim.class), retryPolicy);
    TaskMessage untrusted = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_EVALUATION, "resume:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(untrusted)).thenThrow(new IllegalArgumentException("wrong type"));
    org.mockito.Mockito.doThrow(new AmqpException("publisher unavailable"))
        .when(retryPolicy).routeFailure(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.same(source));

    assertThatThrownBy(() -> listener.receive(untrusted, source))
        .isInstanceOf(AmqpException.class);

    var routed = ArgumentCaptor.forClass(TaskMessage.class);
    verify(retryPolicy, times(1)).routeFailure(routed.capture(),
        org.mockito.ArgumentMatchers.same(source));
    org.assertj.core.api.Assertions.assertThat(routed.getValue().taskType())
        .isEqualTo(AsyncTaskType.RESUME_ANALYSIS);
  }

  @Test
  void typedRetryPublishFailureMustEscapeSoTheDeliveryIsNotAcknowledged() {
    ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new ResumeAnalysisListener(handler, claims, retryPolicy);
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(new ResumeAnalysisHandler.ResumeAnalysisTarget(42L, false));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.handle(task)).thenThrow(new ResumeAnalysisRetryableException(1));
    when(retryPolicy.routeFailure(task, source))
        .thenThrow(new AmqpException("publisher unavailable"));

    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
  }

  @Test
  void dlqSuccessFollowedByTerminalDatabaseFailureMustEscape() {
    ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new ResumeAnalysisListener(handler, claims, retryPolicy);
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(new ResumeAnalysisHandler.ResumeAnalysisTarget(42L, false));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.handle(task)).thenThrow(new ResumeAnalysisRetryableException(1));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 1))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> listener.receive(task, source))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exhaustedInspectFailureMustTerminalizeOnlyAValidatedCurrentMessage() {
    ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new ResumeAnalysisListener(
        handler, mock(ProcessingClaim.class), retryPolicy);
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database read failed"));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDeadCurrent(task)).thenReturn(true);

    listener.receive(task, source);

    verify(handler).markDeadCurrent(task);
  }

  @Test
  void exhaustedOccupiedClaimMustNotTerminalizeAnotherOwnersGeneration() {
    ResumeFixture fixture = resumeFixture();
    when(fixture.claims.acquire(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());
    when(fixture.retries.routeFailure(fixture.task, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);

    assertThatThrownBy(() -> fixture.listener.receive(fixture.task, fixture.source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Resume analysis dead-letter state could not be persisted");
    verify(fixture.handler, org.mockito.Mockito.never())
        .markDead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void exhaustedClaimInfrastructureFailureMustNotTerminalizeWithoutOwnership() {
    ResumeFixture fixture = resumeFixture();
    when(fixture.claims.acquire(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("redis unavailable"));
    when(fixture.retries.routeFailure(fixture.task, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);

    assertThatThrownBy(() -> fixture.listener.receive(fixture.task, fixture.source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Resume analysis dead-letter state could not be persisted");
    verify(fixture.handler, org.mockito.Mockito.never())
        .markDead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void staleOwnedDeliveryReleasesItsTokenInsteadOfWritingDoneMarker() {
    ResumeFixture fixture = resumeFixture();
    when(fixture.handler.handle(fixture.task)).thenReturn(ResumeAnalysisHandler.Outcome.STALE);

    fixture.listener.receive(fixture.task, fixture.source);

    verify(fixture.claims).release(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq("token"));
    verify(fixture.claims, org.mockito.Mockito.never()).complete(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  private ResumeFixture resumeFixture() {
    ResumeAnalysisHandler handler = mock(ResumeAnalysisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
    var listener = new ResumeAnalysisListener(handler, claims, retries);
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new ResumeAnalysisHandler.ResumeAnalysisTarget(42L, false));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of("token"));
    return new ResumeFixture(handler, claims, retries, listener, task, source);
  }

  private record ResumeFixture(
      ResumeAnalysisHandler handler,
      ProcessingClaim claims,
      TaskRetryPolicy retries,
      ResumeAnalysisListener listener,
      TaskMessage task,
      Message source) {}
}
