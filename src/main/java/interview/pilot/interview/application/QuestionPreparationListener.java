package interview.pilot.interview.application;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

@Component
public class QuestionPreparationListener {
  private final QuestionPreparationHandler handler;
  private final TaskRetryPolicy retries;

  public QuestionPreparationListener(QuestionPreparationHandler handler, TaskRetryPolicy retries) {
    this.handler = handler;
    this.retries = retries;
  }

  @RabbitListener(queues = RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE,
      autoStartup = "${app.async.interview-preparation-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    if (message == null) throw new IllegalArgumentException("Question preparation message is required");
    var retryMessage = new TaskMessage(message.taskId(), AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        message.bizKey(), message.executionEpoch());
    QuestionPreparationHandler.Outcome outcome;
    try {
      if (handler.inspect(message).terminal()) return;
      outcome = handler.prepare(message);
    } catch (QuestionPreparationExecutionException exception) {
      int generation = exception.attemptGeneration();
      String token = exception.executionToken();
      if (exception.getCause() instanceof InvalidQuestionDeckException
          || exception.getCause() instanceof AiStructuredOutputException) {
        handler.markInvalid(message, generation, token);
      } else if (exception.getCause() instanceof OptimisticLockingFailureException) {
        retries.defer(retryMessage, source);
        handler.releaseForRetry(message, generation, token);
      } else if (retries.routeFailure(retryMessage, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER) {
        handler.markDead(message, generation, token);
      } else {
        // The retry must be durable before this execution gives up its database lease.
        handler.releaseForRetry(message, generation, token);
      }
      return;
    } catch (RuntimeException exception) {
      // Without a committed execution identity, never consume retries or fail another owner.
      retries.defer(retryMessage, source);
      return;
    }
    if (outcome == QuestionPreparationHandler.Outcome.BUSY) retries.defer(retryMessage, source);
  }
}
