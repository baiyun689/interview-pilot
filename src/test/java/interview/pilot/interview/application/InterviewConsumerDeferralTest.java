package interview.pilot.interview.application;

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

class InterviewConsumerDeferralTest {
  private final FixedInterviewReportHandler handler = mock(FixedInterviewReportHandler.class);
  private final TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
  private final InterviewReportListener listener = new InterviewReportListener(handler, retries);
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.INTERVIEW_EVALUATION,
      "interview:" + UUID.randomUUID());
  private final Message source = new Message(new byte[0], new MessageProperties());

  @Test void databaseOutageDefersWithoutInventingAnExecution() {
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database down"));
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
    verify(handler, never()).handle(any(), anyInt());
  }

  @Test void failedBusyDeferralEscapesWithoutConsumingTheLastRetry() {
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    when(handler.inspect(task)).thenReturn(new FixedInterviewReportHandler.Target(UUID.randomUUID(), false, 3));
    when(handler.handle(any(), anyInt())).thenReturn(FixedInterviewReportHandler.Outcome.BUSY);
    doThrow(new AmqpException("broker down")).when(retries).defer(task, source);
    assertThatThrownBy(() -> listener.receive(task, source)).isInstanceOf(AmqpException.class);
    verify(retries, never()).routeFailure(any(), any());
  }
}
