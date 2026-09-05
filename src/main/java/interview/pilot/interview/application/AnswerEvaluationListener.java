package interview.pilot.interview.application;

import java.time.Duration;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;

/**
 * Consumer of the {@code interview-pilot.interview.answer-evaluation.main} queue: inspect →
 * acquire the per-turn processing claim (the bizKey, shared with the retry policy) → evaluate →
 * release/complete. Operational failures ride the shared 5s/30s/120s delayed-retry ladder; on
 * dead-letter the handler marks only eval_status FAILED, so neither the next question nor report
 * generation is blocked. A duplicate delivery while the owner runs is dropped without consuming
 * its retry budget.
 */
@Component
public class AnswerEvaluationListener {
  private static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final AnswerEvaluationHandler handler;
  private final ProcessingClaim claims;
  private final TaskRetryPolicy retries;

  public AnswerEvaluationListener(
      AnswerEvaluationHandler handler, ProcessingClaim claims, TaskRetryPolicy retries) {
    this.handler = handler;
    this.claims = claims;
    this.retries = retries;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.ANSWER_EVALUATION_MAIN_QUEUE,
      autoStartup = "${app.async.answer-evaluation-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    AnswerEvaluationHandler.Target target = handler.inspect(message);
    if (target.terminal()) {
      return;
    }
    String claimKey = message.bizKey();
    String token;
    try {
      token = claims.acquire(claimKey, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      token = "";
    }
    if (token == null) {
      return;
    }
    try {
      AnswerEvaluationHandler.Outcome outcome = handler.evaluate(message);
      if (outcome == AnswerEvaluationHandler.Outcome.TERMINAL) {
        completeBestEffort(claimKey, token);
      } else {
        releaseBestEffort(claimKey, token);
      }
    } catch (RuntimeException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(message, source, target.attemptGeneration());
    }
  }

  private void completeBestEffort(String key, String token) {
    if (token.isEmpty()) {
      return;
    }
    try {
      claims.complete(key, token, COMPLETED_TTL);
    } catch (RuntimeException ignored) {
      // MySQL terminal state is authoritative; Redis completion is best effort.
    }
  }

  private void releaseBestEffort(String key, String token) {
    if (token.isEmpty()) {
      return;
    }
    try {
      claims.release(key, token);
    } catch (RuntimeException ignored) {
      // The short TTL makes a lost release recoverable.
    }
  }

  private void routeFailure(TaskMessage message, Message source, int attemptGeneration) {
    if (retries.routeFailure(message, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !handler.markDead(message, attemptGeneration)) {
      throw new IllegalStateException("Answer evaluation dead-letter state could not be persisted");
    }
  }
}
