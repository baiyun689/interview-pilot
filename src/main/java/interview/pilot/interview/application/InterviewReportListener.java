package interview.pilot.interview.application;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.*;
import interview.pilot.interview.application.FixedInterviewReportHandler;
import interview.pilot.interview.application.ReportGenerationRetryableException;

/** MySQL owns execution; publish confirmation precedes lease release. */
@Component
public class InterviewReportListener {
  private final FixedInterviewReportHandler handler;
  private final TaskRetryPolicy retries;

  public InterviewReportListener(FixedInterviewReportHandler handler, TaskRetryPolicy retries) {
    this.handler = handler;
    this.retries = retries;
  }

  @RabbitListener(queues = RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE,
      autoStartup = "${app.async.interview-report-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    if (message == null) throw new IllegalArgumentException("Task message is required");
    var retryMessage = new TaskMessage(message.taskId(), AsyncTaskType.INTERVIEW_EVALUATION, message.bizKey(), message.executionEpoch());
    FixedInterviewReportHandler.Outcome outcome;
    try {
      if (handler.inspect(message).terminal()) return;
      outcome = handler.handle(message, retries.retryCountOf(source));
    } catch (ReportGenerationRetryableException exception) {
      String token = exception.executionToken();
      if (token == null) {
        // Failure before a committed claim cannot terminate another execution.
        retries.defer(retryMessage, source);
      } else if (exception.getCause() instanceof OptimisticLockingFailureException) {
        retries.defer(retryMessage, source);
        handler.releaseForRetry(message, exception.attemptGeneration(), token);
      } else if (retries.routeFailure(retryMessage, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER) {
        handler.markDead(message, exception.attemptGeneration(), token);
      } else {
        handler.releaseForRetry(message, exception.attemptGeneration(), token);
      }
      return;
    } catch (RuntimeException exception) {
      retries.defer(retryMessage, source);
      return;
    }
    if (outcome == FixedInterviewReportHandler.Outcome.BUSY) retries.defer(retryMessage, source);
  }
}
