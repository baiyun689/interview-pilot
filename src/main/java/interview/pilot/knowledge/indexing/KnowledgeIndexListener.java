package interview.pilot.knowledge.indexing;

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
import interview.pilot.async.policy.AbstractKnowledgeDocumentRetryPolicy;

@Component
public class KnowledgeIndexListener {
  static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final KnowledgeIndexHandler handler;
  private final ProcessingClaim claims;
  private final TaskRetryPolicy retries;

  public KnowledgeIndexListener(
      KnowledgeIndexHandler handler, ProcessingClaim claims, TaskRetryPolicy retries) {
    this.handler = handler;
    this.claims = claims;
    this.retries = retries;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.KNOWLEDGE_INDEX_MAIN_QUEUE,
      autoStartup = "${app.async.knowledge-index-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    TaskMessage indexMessage = pipelineMessage(message);
    KnowledgeIndexHandler.IndexTarget target;
    try {
      target = handler.inspect(message);
    } catch (RuntimeException exception) {
      routeFailure(indexMessage, source, () -> handler.markDeadCurrent(message));
      return;
    }
    if (target.terminal()) return;

    String claimKey = AbstractKnowledgeDocumentRetryPolicy.CLAIM_KEY_PREFIX + target.documentUuid();
    String token;
    try {
      token = claims.acquire(claimKey, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      routeFailure(indexMessage, source, () -> false);
      return;
    }
    if (token == null) {
      routeFailure(indexMessage, source, () -> false);
      return;
    }

    try {
      KnowledgeIndexHandler.Outcome outcome = handler.handle(message);
      if (outcome == KnowledgeIndexHandler.Outcome.TERMINAL) {
        try {
          claims.complete(claimKey, token, COMPLETED_TTL);
        } catch (RuntimeException ignored) {
          // Durable MySQL state is authoritative.
        }
      } else if (outcome == KnowledgeIndexHandler.Outcome.STALE) {
        releaseBestEffort(claimKey, token);
      }
    } catch (KnowledgeIndexRetryableException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(indexMessage, source,
          () -> handler.markDead(message, exception.attemptGeneration()));
    } catch (RuntimeException exception) {
      releaseBestEffort(claimKey, token);
      routeFailure(indexMessage, source,
          () -> handler.markDead(message, target.attemptGeneration()));
    }
  }

  private void routeFailure(
      TaskMessage message, Message source, BooleanSupplier terminalizeDeadLetter) {
    TaskRetryPolicy.RouteOutcome route = retries.routeFailure(message, source);
    if (route == TaskRetryPolicy.RouteOutcome.DEAD_LETTER
        && !terminalizeDeadLetter.getAsBoolean()) {
      throw new IllegalStateException(
          "Knowledge index dead-letter state could not be persisted");
    }
  }

  private void releaseBestEffort(String claimKey, String token) {
    try {
      claims.release(claimKey, token);
    } catch (RuntimeException ignored) {
      // The short TTL and MySQL generation fence make a lost Redis release recoverable.
    }
  }

  private TaskMessage pipelineMessage(TaskMessage message) {
    if (message == null) throw new IllegalArgumentException("Knowledge index message is required");
    return new TaskMessage(
        message.taskId(), AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX,
        message.bizKey(), message.executionEpoch());
  }
}
