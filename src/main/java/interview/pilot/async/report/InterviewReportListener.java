package interview.pilot.async.report;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.interview.application.InterviewReportHandler;
import interview.pilot.interview.application.ReportGenerationRetryableException;

@Component
public class InterviewReportListener {
  static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final InterviewReportHandler handler;
  private final ProcessingClaim claims;
  private final TaskRetryPolicy retries;

  public InterviewReportListener(
      InterviewReportHandler handler, ProcessingClaim claims, TaskRetryPolicy retries) {
    this.handler = handler;
    this.claims = claims;
    this.retries = retries;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE,
      autoStartup = "${app.async.interview-report-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    TaskMessage reportMessage = reportPipelineMessage(message);
    InterviewReportHandler.Target target;
    try {
      target = handler.inspect(message);
    } catch (RuntimeException exception) {
      routeFailure(reportMessage, source, () -> handler.markDeadCurrent(message));
      return;
    }
    if (target.terminal()) return;

    String claimKey = "interview-report:" + target.sessionId();
    String token;
    try {
      token = claims.acquire(claimKey, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      routeFailure(reportMessage, source, () -> false);
      return;
    }
    if (token == null) {
      routeFailure(reportMessage, source, () -> false);
      return;
    }

    try {
      InterviewReportHandler.Outcome outcome = handler.handle(message);
      if (outcome == InterviewReportHandler.Outcome.TERMINAL) {
        try {
          claims.complete(claimKey, token, COMPLETED_TTL);
        } catch (RuntimeException ignored) {
          // Durable MySQL state is authoritative.
        }
      } else if (outcome == InterviewReportHandler.Outcome.STALE) {
        releaseBestEffort(claimKey, token);
      }
    } catch (ReportGenerationRetryableException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(reportMessage, source,
          () -> handler.markDead(message, exception.attemptGeneration()));
    } catch (RuntimeException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(reportMessage, source,
          () -> handler.markDead(message, target.attemptGeneration()));
    }
  }

  private void routeFailure(
      TaskMessage message, Message source, BooleanSupplier terminalizeDeadLetter) {
    TaskRetryPolicy.RouteOutcome route = retries.routeFailure(message, source);
    if (route == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !terminalizeDeadLetter.getAsBoolean()) {
      throw new IllegalStateException(
          "Interview report dead-letter state could not be persisted");
    }
  }

  private void releaseBestEffort(String claimKey, String token) {
    try {
      claims.release(claimKey, token);
    } catch (RuntimeException ignored) {
      // The short TTL and MySQL generation fence make a lost Redis release recoverable.
    }
  }

  private TaskMessage reportPipelineMessage(TaskMessage message) {
    if (message == null) throw new IllegalArgumentException("Interview report message is required");
    return new TaskMessage(
        message.taskId(), AsyncTaskType.INTERVIEW_EVALUATION,
        message.bizKey(), message.executionEpoch());
  }
}
