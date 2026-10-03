package interview.pilot.voice.infrastructure;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.messaging.*;
import interview.pilot.voice.application.VoiceTranscriptionHandler;
import interview.pilot.voice.application.VoiceTranscriptionRetryableException;

class VoiceTranscriptionListenerTest {
  private final VoiceTranscriptionHandler handler = mock(VoiceTranscriptionHandler.class);
  private final TaskRetryPolicy retries = mock(TaskRetryPolicy.class);
  private final VoiceTranscriptionListener listener = new VoiceTranscriptionListener(handler, retries);
  private final TaskMessage task = new TaskMessage(UUID.randomUUID(), AsyncTaskType.VOICE_TRANSCRIPTION, "test");
  private final Message source = new Message(new byte[0], new MessageProperties());
  @BeforeEach void setup() { when(handler.inspect(task)).thenReturn(new VoiceTranscriptionHandler.VoiceTranscriptionTarget(UUID.randomUUID(), false, 3, 0)); }

  @Test void busyDefersWithoutConsumingFailureBudget() {
    when(handler.transcribe(task)).thenReturn(VoiceTranscriptionHandler.Outcome.BUSY);
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(retries, never()).routeFailure(any(), any());
    verify(handler, never()).markDead(any(), anyInt(), any());
  }
  @Test void failedDeferralEscapesToKeepDeliveryUnacknowledged() {
    when(handler.transcribe(task)).thenReturn(VoiceTranscriptionHandler.Outcome.BUSY);
    doThrow(new IllegalStateException("broker down")).when(retries).defer(task, source);
    assertThatThrownBy(() -> listener.receive(task, source)).hasMessage("broker down");
  }
  @Test void inspectFailureDoesNotInventAnExecutionIdentity() {
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database down"));
    listener.receive(task, source);
    verify(retries).defer(task, source);
    verify(handler, never()).markDead(any(), anyInt(), any());
  }
  @Test void retryIsConfirmedBeforeRelease() {
    when(handler.transcribe(task)).thenThrow(new VoiceTranscriptionRetryableException("unavailable", 4, "owner"));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.RETRY);
    listener.receive(task, source);
    var order = inOrder(retries, handler);
    order.verify(retries).routeFailure(task, source);
    order.verify(handler).releaseForRetry(task, 4, "owner");
  }
  @Test void failedPublicationKeepsTheLease() {
    when(handler.transcribe(task)).thenThrow(new VoiceTranscriptionRetryableException("unavailable", 4, "owner"));
    when(retries.routeFailure(task, source)).thenThrow(new IllegalStateException("broker down"));
    assertThatThrownBy(() -> listener.receive(task, source)).hasMessage("broker down");
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }
  @Test void exhaustionUsesCommittedIdentityInsteadOfInspectionGeneration() {
    when(handler.transcribe(task)).thenThrow(new VoiceTranscriptionRetryableException("unavailable", 4, "owner"));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    listener.receive(task, source);
    verify(handler).markDead(task, 4, "owner");
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }
  @Test void deadLetterPersistenceFailureEscapes() {
    when(handler.transcribe(task)).thenThrow(new VoiceTranscriptionRetryableException("unavailable", 4, "owner"));
    when(retries.routeFailure(task, source)).thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 4, "owner")).thenThrow(new IllegalStateException("database down"));
    assertThatThrownBy(() -> listener.receive(task, source)).hasMessage("database down");
  }
  @Test void staleResultDoesNotRetryOrReleaseAnotherOwner() {
    when(handler.transcribe(task)).thenReturn(VoiceTranscriptionHandler.Outcome.STALE);
    listener.receive(task, source);
    verifyNoInteractions(retries);
    verify(handler, never()).releaseForRetry(any(), anyInt(), any());
  }
}
