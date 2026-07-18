package interview.pilot.async.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.interview.application.InterviewReportHandler;
import interview.pilot.interview.application.ReportGenerationRetryableException;

class InterviewReportListenerTest {
  @Test
  void typedRetryPublishFailureMustEscapeSoTheDeliveryIsNotAcknowledged() {
    Fixture fixture = fixture();
    when(fixture.handler.handle(fixture.message))
        .thenThrow(new ReportGenerationRetryableException(1));
    when(fixture.retries.routeFailure(fixture.message, fixture.source))
        .thenThrow(new AmqpException("publisher unavailable"));

    assertThatThrownBy(() -> fixture.listener.receive(fixture.message, fixture.source))
        .isInstanceOf(AmqpException.class);
  }

  @Test
  void dlqSuccessFollowedByTerminalDatabaseFailureMustEscape() {
    Fixture fixture = fixture();
    when(fixture.handler.handle(fixture.message))
        .thenThrow(new ReportGenerationRetryableException(1));
    when(fixture.retries.routeFailure(fixture.message, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(fixture.handler.markDead(fixture.message, 1))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> fixture.listener.receive(fixture.message, fixture.source))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exhaustedGenericFailureMustTerminalizeTheCurrentGeneration() {
    Fixture fixture = fixture();
    when(fixture.handler.handle(fixture.message))
        .thenThrow(new IllegalStateException("corrupt evidence"));
    when(fixture.retries.routeFailure(fixture.message, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(fixture.handler.markDead(fixture.message, 0)).thenReturn(true);

    fixture.listener.receive(fixture.message, fixture.source);

    verify(fixture.handler).markDead(fixture.message, 0);
  }

  @Test
  void exhaustedOccupiedClaimMustNotTerminalizeAnotherOwnersGeneration() {
    Fixture fixture = fixture();
    when(fixture.claims.acquire(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());
    when(fixture.retries.routeFailure(fixture.message, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    assertThatThrownBy(() -> fixture.listener.receive(fixture.message, fixture.source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Interview report dead-letter state could not be persisted");
    verify(fixture.handler, org.mockito.Mockito.never())
        .markDead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void exhaustedClaimInfrastructureFailureMustNotTerminalizeWithoutOwnership() {
    Fixture fixture = fixture();
    when(fixture.claims.acquire(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("redis unavailable"));
    when(fixture.retries.routeFailure(fixture.message, fixture.source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);

    assertThatThrownBy(() -> fixture.listener.receive(fixture.message, fixture.source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Interview report dead-letter state could not be persisted");
    verify(fixture.handler, org.mockito.Mockito.never())
        .markDead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void staleOwnedDeliveryReleasesItsTokenInsteadOfWritingDoneMarker() {
    Fixture fixture = fixture();
    when(fixture.handler.handle(fixture.message)).thenReturn(InterviewReportHandler.Outcome.STALE);

    fixture.listener.receive(fixture.message, fixture.source);

    verify(fixture.claims).release(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq("token"));
    verify(fixture.claims, org.mockito.Mockito.never()).complete(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  private Fixture fixture() {
    InterviewReportHandler handler = mock(InterviewReportHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
    var listener = new InterviewReportListener(handler, claims, retries);
    TaskMessage message = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + UUID.randomUUID());
    Message source = new Message(new byte[0], new MessageProperties());
    UUID sessionId = UUID.fromString(message.bizKey().substring("interview:".length()));
    when(handler.inspect(message)).thenReturn(new InterviewReportHandler.Target(sessionId, false));
    when(claims.acquire(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    return new Fixture(handler, claims, retries, listener, message, source);
  }

  private record Fixture(
      InterviewReportHandler handler,
      ProcessingClaim claims,
      TaskRetryPolicy retries,
      InterviewReportListener listener,
      TaskMessage message,
      Message source) {}
}
