package interview.pilot.interview.application;

import java.time.Duration;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.InterviewEvaluationRetryPolicy;

@Component
public class InterviewReportListener {
  private static final Duration PROCESSING_TTL = Duration.ofMinutes(11);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);
  private final FixedInterviewReportHandler handler;
  private final ProcessingClaim claims;
  private final TaskRetryPolicy retries;

  public InterviewReportListener(
      FixedInterviewReportHandler handler, ProcessingClaim claims, TaskRetryPolicy retries) {
    this.handler = handler;
    this.claims = claims;
    this.retries = retries;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.INTERVIEW_REPORT_MAIN_QUEUE,
      autoStartup = "${app.async.interview-report-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    FixedInterviewReportHandler.Target target = handler.inspect(message);
    if (target.terminal()) return;
    String key = InterviewEvaluationRetryPolicy.CLAIM_KEY_PREFIX + target.sessionId();
    String token;
    try {
      token = claims.acquire(key, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      token = "";
    }
    if (token == null) {
      // A duplicate delivery must not dead-letter a task while its owner is still running.
      return;
    }
    try {
      var outcome = handler.handle(message, retries.retryCountOf(source));
      if (outcome == FixedInterviewReportHandler.Outcome.TERMINAL) {
        completeBestEffort(key, token);
      } else {
        releaseBestEffort(key, token);
      }
    } catch (ReportGenerationRetryableException exception) {
      releaseBestEffort(key, token);
      routeFailure(message, source, exception.attemptGeneration());
    } catch (RuntimeException exception) {
      releaseBestEffort(key, token);
      routeFailure(message, source, target.attemptGeneration());
    }
  }

  private void completeBestEffort(String key, String token) {
    if (token.isEmpty()) return;
    try { claims.complete(key, token, COMPLETED_TTL); } catch (RuntimeException ignored) { }
  }

  private void releaseBestEffort(String key, String token) {
    if (token.isEmpty()) return;
    try { claims.release(key, token); } catch (RuntimeException ignored) { }
  }

  private void routeFailure(TaskMessage message, Message source, int attemptGeneration) {
    if (retries.routeFailure(message, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !handler.markDead(message, attemptGeneration)) {
      throw new IllegalStateException("Interview report dead-letter state could not be persisted");
    }
  }
}
