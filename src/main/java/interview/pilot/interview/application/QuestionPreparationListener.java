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
      // A database conflict is not proof that another live consumer owns this delivery.
      retries.defer(message, source);
      return;
    }
    if (target.terminal()) return;
    String key = InterviewPreparationRetryPolicy.CLAIM_KEY_PREFIX + target.sessionId();
    String token;
    try {
      token = claims.acquire(key, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      retries.defer(message, source);
      return;
    }
    if (token == null) {
      retries.defer(message, source);
      return;
    }
    try {
      var outcome = handler.prepare(message);
      if (outcome == QuestionPreparationHandler.Outcome.COMPLETED) {
        completeBestEffort(key, token);
      } else {
        releaseBestEffort(key, token);
      }
    } catch (QuestionPreparationExecutionException exception) {
      releaseBestEffort(key, token);
      if (exception.getCause() instanceof InvalidQuestionDeckException
          || exception.getCause() instanceof AiStructuredOutputException) {
        handler.markInvalid(message, exception.attemptGeneration());
      } else if (exception.getCause() instanceof OptimisticLockingFailureException) {
        retries.defer(message, source);
      } else {
        routeRetry(message, source, exception.attemptGeneration());
      }
    } catch (OptimisticLockingFailureException exception) {
      releaseBestEffort(key, token);
      retries.defer(message, source);
    } catch (RuntimeException exception) {
      releaseBestEffort(key, token);
      routeRetry(message, source, target.attemptGeneration());
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

  private void routeRetry(TaskMessage message, Message source, int attemptGeneration) {
    if (retries.routeFailure(message, source) == TaskRetryPolicy.RouteOutcome.DEAD_LETTER) {
      // false means a newer generation or a terminal session; database failures still escape.
      handler.markDead(message, attemptGeneration);
    }
  }
}
