package interview.pilot.interview.application;

import java.time.Duration;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.InterviewPreparationRetryPolicy;

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
    QuestionPreparationHandler.Target target;
    try {
      target = handler.inspect(message);
    } catch (OptimisticLockingFailureException exception) {
      // A concurrent owner already moved this task forward. This stale delivery is not a
      // provider failure and must not burn the RabbitMQ retry budget.
      return;
    }
    if (target.terminal()) return;
    String key = InterviewPreparationRetryPolicy.CLAIM_KEY_PREFIX + target.sessionId();
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
    } catch (OptimisticLockingFailureException exception) {
      releaseBestEffort(key, token);
      // Persisting a stale execution lost the database ownership race; leave retry ownership
      // with the winner instead of turning a benign conflict into a dead-letter failure.
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
