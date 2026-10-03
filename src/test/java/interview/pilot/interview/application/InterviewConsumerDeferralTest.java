package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.*;

class InterviewConsumerDeferralTest {
  enum Pipeline { EVALUATION, REPORT }

  @ParameterizedTest @EnumSource(Pipeline.class)
  void redisOutageDefersInsteadOfStartingAnUncoordinatedModelCall(Pipeline pipeline) {
    var fixture = fixture(pipeline);
    when(fixture.claims.acquire(anyString(), any())).thenThrow(new IllegalStateException("redis down"));
    fixture.receive.run();
    verify(fixture.retries).defer(fixture.task, fixture.source);
    verify(fixture.retries, never()).routeFailure(any(), any());
    fixture.verifyNoExecution.run();
  }

  @ParameterizedTest @EnumSource(Pipeline.class)
  void failedDeferralEscapesSoTheOriginalDeliveryRemainsUnacknowledged(Pipeline pipeline) {
    var fixture = fixture(pipeline);
    when(fixture.claims.acquire(anyString(), any())).thenReturn(Optional.empty());
    doThrow(new AmqpException("broker down")).when(fixture.retries).defer(fixture.task, fixture.source);
    assertThatThrownBy(fixture.receive::run).isInstanceOf(AmqpException.class);
    verify(fixture.retries, never()).routeFailure(any(), any());
    fixture.verifyNoExecution.run();
  }

  private Fixture fixture(Pipeline pipeline) {
    var claims = mock(ProcessingClaim.class);
    var retries = mock(TaskRetryPolicy.class);
    UUID sessionId = UUID.randomUUID();
    AsyncTaskType type = switch (pipeline) {

      case EVALUATION -> AsyncTaskType.ANSWER_EVALUATION;
      case REPORT -> AsyncTaskType.INTERVIEW_EVALUATION;
    };
    var task = new TaskMessage(UUID.randomUUID(), type, "interview:" + sessionId);
    var source = new Message(new byte[0], new MessageProperties());
    source.getMessageProperties().setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    return switch (pipeline) {

      case EVALUATION -> {
        var handler = mock(AnswerEvaluationHandler.class);
        when(handler.inspect(task)).thenReturn(new AnswerEvaluationHandler.Target(sessionId, false, 3, 0));
        var listener = new AnswerEvaluationListener(handler, claims, retries);
        yield new Fixture(claims, retries, task, source, () -> listener.receive(task, source),
            () -> verify(handler, never()).evaluate(any()));
      }
      case REPORT -> {
        var handler = mock(FixedInterviewReportHandler.class);
        when(handler.inspect(task)).thenReturn(new FixedInterviewReportHandler.Target(sessionId, false, 3));
        var listener = new InterviewReportListener(handler, claims, retries);
        yield new Fixture(claims, retries, task, source, () -> listener.receive(task, source),
            () -> verify(handler, never()).handle(any(), anyInt()));
      }
    };
  }

  private record Fixture(ProcessingClaim claims, TaskRetryPolicy retries, TaskMessage task,
      Message source, Runnable receive, Runnable verifyNoExecution) {}
}
