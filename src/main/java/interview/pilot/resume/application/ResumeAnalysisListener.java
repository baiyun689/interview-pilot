package interview.pilot.resume.application;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.ResumeAnalysisRetryPolicy;

@Component
public class ResumeAnalysisListener {
  static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final ResumeAnalysisHandler handler;
  private final ProcessingClaim processingClaim;
  private final TaskRetryPolicy retryPolicy;

  public ResumeAnalysisListener(
      ResumeAnalysisHandler handler,
      ProcessingClaim processingClaim,
      TaskRetryPolicy retryPolicy) {
    this.handler = handler;
    this.processingClaim = processingClaim;
    this.retryPolicy = retryPolicy;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.RESUME_ANALYSIS_MAIN_QUEUE,
      autoStartup = "${app.async.resume-analysis-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    TaskMessage retryMessage = resumePipelineMessage(message);
    ResumeAnalysisHandler.ResumeAnalysisTarget target;
    try {
      target = handler.inspect(message);
    } catch (RuntimeException exception) {
      routeFailure(retryMessage, source, () -> handler.markDeadCurrent(message));
      return;
    }
    if (target.terminal()) {
      return;
    }

    String claimKey = ResumeAnalysisRetryPolicy.CLAIM_KEY_PREFIX + target.resumeId();
    String token;
    try {
      token = processingClaim.acquire(claimKey, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      routeFailure(retryMessage, source, () -> false);
      return;
    }
    if (token == null) {
      routeFailure(retryMessage, source, () -> false);
      return;
    }

    ResumeAnalysisHandler.Outcome outcome;
    try {
      outcome = handler.handle(message);
    } catch (ResumeAnalysisRetryableException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(retryMessage, source,
          () -> handler.markDead(message, exception.attemptGeneration()));
      return;
    } catch (RuntimeException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(retryMessage, source,
          () -> handler.markDead(message, target.attemptGeneration()));
      return;
    }
    if (outcome == ResumeAnalysisHandler.Outcome.STALE) {
      releaseBestEffort(claimKey, token);
      return;
    }
    try {
      processingClaim.complete(claimKey, token, COMPLETED_TTL);
    } catch (RuntimeException ignored) {
      // MySQL terminal state is authoritative; Redis completion is best effort.
    }
  }

  private void routeFailure(
      TaskMessage message, Message source, BooleanSupplier terminalizeDeadLetter) {
    TaskRetryPolicy.RouteOutcome route = retryPolicy.routeFailure(message, source);
    if (route == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !terminalizeDeadLetter.getAsBoolean()) {
      throw new IllegalStateException(
          "Resume analysis dead-letter state could not be persisted");
    }
  }

  private void releaseBestEffort(String claimKey, String token) {
    try {
      processingClaim.release(claimKey, token);
    } catch (RuntimeException ignored) {
      // The short TTL and MySQL attempt fence make a lost release recoverable.
    }
  }

  private TaskMessage resumePipelineMessage(TaskMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("Resume analysis message is required");
    }
    return new TaskMessage(
        message.taskId(), AsyncTaskType.RESUME_ANALYSIS,
        message.bizKey(), message.executionEpoch());
  }
}
