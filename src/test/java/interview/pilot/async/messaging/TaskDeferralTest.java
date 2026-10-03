package interview.pilot.async.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import interview.pilot.async.domain.AsyncTaskType;

class TaskDeferralTest {
  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final AsyncRabbitProperties properties = new AsyncRabbitProperties();
  private final TaskRetryPolicy retries = new TaskRetryPolicy(new TaskMessagePublisher(rabbit, properties));
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.RESUME_ANALYSIS, "resume:42", 2);

  @Test void confirmedDeferralPreservesTheTaskEpochAndExhaustedRetryCount() {
    var route = RabbitTopologyConfig.routeFor(task.taskType());
    doAnswer(call -> {
      assertThat((TaskMessage) call.getArgument(2)).isEqualTo(task);
      Message deferred = call.<MessagePostProcessor>getArgument(3).postProcessMessage(source(0));
      assertThat((Object) deferred.getMessageProperties().getHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER))
          .isEqualTo(3);
      call.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).convertAndSend(eq(route.mainExchange()), eq(route.retryRoutingKey(2)),
        eq(task), any(MessagePostProcessor.class), any(CorrelationData.class));
    retries.defer(task, source(3));
    verify(rabbit).convertAndSend(eq(route.mainExchange()), eq(route.retryRoutingKey(2)),
        eq(task), any(MessagePostProcessor.class), any(CorrelationData.class));
  }

  @Test void brokerNackMustEscapeSoTheOriginalDeliveryCannotBeAcknowledged() {
    respond(false, false);
    assertThatThrownBy(() -> retries.defer(task, source(3))).isInstanceOf(AmqpException.class);
  }

  @Test void unroutableConfirmedMessageMustNotAcknowledgeTheOriginalDelivery() {
    respond(true, true);
    assertThatThrownBy(() -> retries.defer(task, source(3))).isInstanceOf(AmqpException.class);
  }

  @Test void missingConfirmMustNotAcknowledgeTheOriginalDelivery() {
    properties.setConfirmTimeout(Duration.ofMillis(1));
    assertThatThrownBy(() -> retries.defer(task, source(3))).isInstanceOf(AmqpException.class);
  }

  private void respond(boolean ack, boolean returned) {
    doAnswer(call -> {
      CorrelationData correlation = call.getArgument(4);
      if (returned) correlation.setReturned(new ReturnedMessage(source(3), 312, "NO_ROUTE", "exchange", "route"));
      correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "injected failure"));
      return null;
    }).when(rabbit).convertAndSend(anyString(), anyString(), eq(task),
        any(MessagePostProcessor.class), any(CorrelationData.class));
  }

  private Message source(int count) {
    var properties = new MessageProperties();
    properties.setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, count);
    return new Message(new byte[0], properties);
  }
}
