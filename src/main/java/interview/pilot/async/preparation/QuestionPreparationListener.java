package interview.pilot.async.preparation;

import java.time.Duration;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.interview.application.InvalidQuestionDeckException;
import interview.pilot.interview.application.QuestionPreparationHandler;

@Component
public class QuestionPreparationListener {
  private static final Duration PROCESSING_TTL = Duration.ofMinutes(11);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final QuestionPreparationHandler handler;
  private final ProcessingClaim claims;
  private final TaskRetryPolicy retries;

  public QuestionPreparationListener(
      QuestionPreparationHandler handler,
      ProcessingClaim claims,
      TaskRetryPolicy retries) {
    this.handler = handler;
    this.claims = claims;
    this.retries = retries;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.INTERVIEW_PREPARATION_MAIN_QUEUE,
      autoStartup = "${app.async.interview-preparation-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    QuestionPreparationHandler.Target target = handler.inspect(message);
    if (target.terminal()) return;
    String key = "interview-preparation:" + target.sessionId();
    String token;
    try {
      token = claims.acquire(key, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      token = "";
    }
    if (token == null) {
      // A duplicate delivery must not consume the retry budget of the active owner.
      return;
    }
    try {
      var outcome = handler.prepare(message);
      if (outcome == QuestionPreparationHandler.Outcome.COMPLETED) {
        completeBestEffort(key, token);
      } else {
        releaseBestEffort(key, token);
      }
    } catch (InvalidQuestionDeckException | AiStructuredOutputException exception) {
      releaseBestEffort(key, token);
      handler.markInvalid(message);
    } catch (RuntimeException exception) {
      releaseBestEffort(key, token);
      routeRetry(message, source);
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

  private void routeRetry(TaskMessage message, Message source) {
    if (retries.routeFailure(message, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !handler.markDead(message)) {
      throw new IllegalStateException("Question preparation dead-letter state could not be persisted");
    }
  }
}
