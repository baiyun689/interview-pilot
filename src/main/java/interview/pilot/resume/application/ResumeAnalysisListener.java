package interview.pilot.resume.application;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

/** RabbitMQ drives delivery; MySQL alone owns resume execution and result fencing. */
@Component
public class ResumeAnalysisListener {
  private final ResumeAnalysisHandler handler;
  private final TaskRetryPolicy retryPolicy;

  public ResumeAnalysisListener(ResumeAnalysisHandler handler, TaskRetryPolicy retryPolicy) {
    this.handler = handler;
    this.retryPolicy = retryPolicy;
  }

  @RabbitListener(queues = RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE,
      autoStartup = "${app.async.resume-analysis-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    if (message == null) throw new IllegalArgumentException("Resume analysis message is required");
    var retryMessage = new TaskMessage(message.taskId(), AsyncTaskType.RESUME_ANALYSIS,
        message.bizKey(), message.executionEpoch());
    ResumeAnalysisHandler.ResumeAnalysisTarget target;
    try {
      target = handler.inspect(message);
    } catch (RuntimeException exception) {
      if (retryPolicy.routeFailure(retryMessage, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
          && !handler.markDeadCurrent(message)) {
        throw new IllegalStateException("Resume analysis dead-letter state could not be persisted");
      }
      return;
    }
    if (target.terminal()) return;

    ResumeAnalysisHandler.Outcome outcome;
    try {
      outcome = handler.handle(message);
    } catch (ResumeAnalysisRetryableException exception) {
      // Publish first. A crash or broker failure leaves the DB lease for redelivery/takeover.
      var route = retryPolicy.routeFailure(retryMessage, source);
      if (route == TaskRetryPolicy.RouteOutcome.DEAD_LETTER) {
        // false means this execution was superseded; database failures still escape.
        handler.markDead(message, exception.attemptGeneration(), exception.executionToken());
      } else {
        handler.releaseForRetry(message, exception.attemptGeneration(), exception.executionToken());
      }
      return;
    } catch (RuntimeException exception) {
      // No committed execution identity: never terminate another worker's execution.
      retryPolicy.defer(retryMessage, source);
      return;
    }
    if (outcome == ResumeAnalysisHandler.Outcome.BUSY) retryPolicy.defer(retryMessage, source);
  }
}
