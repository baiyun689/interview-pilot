package interview.pilot.voice.infrastructure;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.VoiceSynthesisHandler;

/**
 * Consumer of the {@code interview-pilot.voice.synthesis.main} queue (plan §11): inspect →
 * acquire the processing claim → synthesize → re-queue or terminalize. The claim key is the
 * voice bizKey (via the public {@link QuestionSpeechSynthesisRetryPolicy#BIZ_KEY_PREFIX}) —
 * never a literal — so a manual retry (Task 8's speech retry endpoint) clears exactly the
 * claim this listener acquires. The provider adapter is reached through the
 * {@code SpeechSynthesizer} seam only; no DashScope structure appears here. The speech's
 * epoch checks (handler-side) fence stale messages: an old message cannot overwrite a manual
 * retry's new result. A synthesis failure never touches the interview — the handler only
 * reads the turn and mutates the question_speech and task rows.
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceSynthesisListener {
  static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);

  private final VoiceSynthesisHandler handler;
  private final ProcessingClaim processingClaim;
  private final TaskRetryPolicy retryPolicy;

  public VoiceSynthesisListener(
      VoiceSynthesisHandler handler,
      ProcessingClaim processingClaim,
      TaskRetryPolicy retryPolicy) {
    this.handler = handler;
    this.processingClaim = processingClaim;
    this.retryPolicy = retryPolicy;
  }

  @RabbitListener(
      queues = RabbitTopologyConfig.VOICE_SYNTHESIS_MAIN_QUEUE,
      autoStartup = "${app.async.voice-synthesis-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    TaskMessage retryMessage = voicePipelineMessage(message);
    VoiceSynthesisHandler.VoiceSynthesisTarget target;
    try {
      target = handler.inspect(message);
    } catch (RuntimeException exception) {
      routeFailure(retryMessage, source, () -> handler.markDeadCurrent(message));
      return;
    }
    if (target.terminal()) {
      return;
    }

    String claimKey = QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + target.speechId();
    String token;
    try {
      token = processingClaim.acquire(claimKey, PROCESSING_TTL).orElse(null);
    } catch (RuntimeException exception) {
      routeFailure(retryMessage, source, () -> false);
      return;
    }
    if (token == null) {
      // A duplicate delivery must not consume the retry budget of the active owner.
      routeFailure(retryMessage, source, () -> false);
      return;
    }

    VoiceSynthesisHandler.Outcome outcome;
    try {
      outcome = handler.synthesize(message);
    } catch (SpeechSynthesisRetryableException exception) {
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
    if (outcome == VoiceSynthesisHandler.Outcome.STALE) {
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
          "Voice synthesis dead-letter state could not be persisted");
    }
  }

  private void releaseBestEffort(String claimKey, String token) {
    try {
      processingClaim.release(claimKey, token);
    } catch (RuntimeException ignored) {
      // The short TTL and MySQL epoch fence make a lost release recoverable.
    }
  }

  private TaskMessage voicePipelineMessage(TaskMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("Voice synthesis message is required");
    }
    return new TaskMessage(
        message.taskId(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        message.bizKey(), message.executionEpoch());
  }
}
