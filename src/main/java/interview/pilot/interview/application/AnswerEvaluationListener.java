package interview.pilot.interview.application;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

/** MySQL owns execution; RabbitMQ confirms every retry before the execution lease is released. */
@Component
public class AnswerEvaluationListener {
  private final AnswerEvaluationHandler handler;
  private final TaskRetryPolicy retries;

  public AnswerEvaluationListener(AnswerEvaluationHandler handler, TaskRetryPolicy retries) {
    this.handler = handler;
    this.retries = retries;
  }

  @RabbitListener(queues = RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_QUEUE,
      autoStartup = "${app.async.answer-evaluation-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    if (message == null) throw new IllegalArgumentException("Answer evaluation message is required");
    var retryMessage = new TaskMessage(message.taskId(), AsyncTaskType.ANSWER_EVALUATION,
        message.bizKey(), message.executionEpoch());
    AnswerEvaluationHandler.Outcome outcome;
    try {
      if (handler.inspect(message).terminal()) return;
      outcome = handler.evaluate(message);
    } catch (AnswerEvaluationRetryableException exception) {
      int generation = exception.attemptGeneration();
      String token = exception.executionToken();
      if (exception.getCause() instanceof OptimisticLockingFailureException) {
        retries.defer(retryMessage, source);
        handler.releaseForRetry(message, generation, token);
      } else if (retries.routeFailure(retryMessage, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER) {
        // A false result means a superseded execution or answer, not a persistence failure.
        handler.markDead(message, generation, token, exception.requestId());
      } else {
        handler.releaseForRetry(message, generation, token);
      }
      return;
    } catch (RuntimeException exception) {
      // No committed identity: defer without exhausting or terminating someone else's work.
      retries.defer(retryMessage, source);
      return;
    }
    if (outcome == AnswerEvaluationHandler.Outcome.BUSY) retries.defer(retryMessage, source);
  }
}
