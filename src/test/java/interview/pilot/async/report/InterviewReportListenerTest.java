package interview.pilot.async.report;

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

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.interview.application.FixedInterviewReportHandler;

class InterviewReportListenerTest {
  @Test
  void duplicateDeliveryDoesNotDeadLetterTheActiveOwner() {
    var handler = mock(FixedInterviewReportHandler.class);
    var claims = mock(ProcessingClaim.class);
    var retries = mock(TaskRetryPolicy.class);
    var listener = new InterviewReportListener(handler, claims, retries);
    var task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_EVALUATION,
        "interview:" + UUID.randomUUID());
    var source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new FixedInterviewReportHandler.Target(UUID.randomUUID(), false, 0));
    when(claims.acquire(anyString(), any())).thenReturn(Optional.empty());

    listener.receive(task, source);

    verify(handler, never()).handle(any());
    verify(retries, never()).routeFailure(any(), any());
  }
}
