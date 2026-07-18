package interview.pilot.async.messaging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskMessagePublisher {
  private final RabbitTemplate rabbitTemplate;
  private final AsyncRabbitProperties properties;

  public TaskMessagePublisher(
      RabbitTemplate rabbitTemplate,
      AsyncRabbitProperties properties) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
  }

  public void publish(TaskMessage task) {
    var route = RabbitTopologyConfig.routeFor(task.taskType());
    sendConfirmed(route.mainExchange(), route.mainRoutingKey(), task, message -> message);
  }

  void publishRetry(TaskMessage task, int retryCount) {
    var route = RabbitTopologyConfig.routeFor(task.taskType());
    sendConfirmed(
        route.mainExchange(),
        route.retryRoutingKey(retryCount),
        task,
        message -> {
          message.getMessageProperties().setHeader(
              RabbitTopologyConfig.RETRY_COUNT_HEADER, retryCount);
          return message;
        });
  }

  void publishDeadLetter(TaskMessage task, int retryCount) {
    var route = RabbitTopologyConfig.routeFor(task.taskType());
    sendConfirmed(
        route.deadLetterExchange(),
        route.deadLetterRoutingKey(),
        task,
        message -> {
          message.getMessageProperties().setHeader(
              RabbitTopologyConfig.RETRY_COUNT_HEADER, retryCount);
          return message;
        });
  }

  private void sendConfirmed(
      String exchange,
      String routingKey,
      TaskMessage task,
      MessagePostProcessor postProcessor) {
    var correlation = new CorrelationData(task.taskId() + ":" + UUID.randomUUID());
    rabbitTemplate.convertAndSend(exchange, routingKey, task, postProcessor, correlation);
    try {
      CorrelationData.Confirm confirm = correlation.getFuture().get(
          properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw new AmqpException("RabbitMQ rejected task message: " + confirm.reason());
      }
      if (correlation.getReturned() != null) {
        throw new AmqpException("RabbitMQ returned unroutable task message");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AmqpException("Interrupted while awaiting RabbitMQ publisher confirm", exception);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException exception) {
      throw new AmqpException("RabbitMQ publisher confirm was not acknowledged", exception);
    }
  }
}
