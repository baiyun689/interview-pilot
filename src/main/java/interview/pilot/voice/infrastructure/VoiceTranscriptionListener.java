package interview.pilot.voice.infrastructure;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.*;
import interview.pilot.voice.application.VoiceTranscriptionHandler;
import interview.pilot.voice.application.VoiceTranscriptionRetryableException;

/** MySQL owns execution; publish confirmation precedes lease release. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceTranscriptionListener {
  private final VoiceTranscriptionHandler handler;
  private final TaskRetryPolicy retries;

  public VoiceTranscriptionListener(VoiceTranscriptionHandler handler, TaskRetryPolicy retries) {
    this.handler = handler;
    this.retries = retries;
  }

  @RabbitListener(queues = RabbitTopologyConfig.VOICE_TRANSCRIPTION_MAIN_QUEUE,
      autoStartup = "${app.async.voice-transcription-listener.auto-startup:true}")
  public void receive(TaskMessage message, Message source) {
    if (message == null) throw new IllegalArgumentException("Task message is required");
    var retryMessage = new TaskMessage(message.taskId(), AsyncTaskType.VOICE_TRANSCRIPTION, message.bizKey(), message.executionEpoch());
    VoiceTranscriptionHandler.Outcome outcome;
    try {
      if (handler.inspect(message).terminal()) return;
      outcome = handler.transcribe(message);
    } catch (VoiceTranscriptionRetryableException exception) {
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
    if (outcome == VoiceTranscriptionHandler.Outcome.BUSY) retries.defer(retryMessage, source);
  }
}
