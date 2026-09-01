package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.async.messaging.TaskRetryPolicy;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.voice.application.SpeechSynthesisRetryableException;
import interview.pilot.voice.application.VoiceSynthesisHandler;

class VoiceSynthesisListenerTest {

  @Test
  void untrustedTaskTypeCannotRouteAVoiceQueueFailureIntoAnotherPipeline() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(
        handler, mock(ProcessingClaim.class), retryPolicy);
    TaskMessage untrusted = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.INTERVIEW_EVALUATION, "question-speech:42");
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(untrusted)).thenThrow(new IllegalArgumentException("wrong type"));
    org.mockito.Mockito.doThrow(new AmqpException("publisher unavailable"))
        .when(retryPolicy).routeFailure(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.same(source));

    assertThatThrownBy(() -> listener.receive(untrusted, source))
        .isInstanceOf(AmqpException.class);

    var routed = ArgumentCaptor.forClass(TaskMessage.class);
    verify(retryPolicy, times(1)).routeFailure(routed.capture(),
        org.mockito.ArgumentMatchers.same(source));
    assertThat(routed.getValue().taskType())
        .isEqualTo(AsyncTaskType.QUESTION_SPEECH_SYNTHESIS);
  }

  @Test
  void acquiresTheClaimWithThePolicyBizKeyConstant() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 0));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.synthesize(task))
        .thenReturn(VoiceSynthesisHandler.Outcome.TERMINAL);

    listener.receive(task, source);

    verify(claims).acquire(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId,
        VoiceSynthesisListener.PROCESSING_TTL);
    verify(claims).complete(
        org.mockito.ArgumentMatchers.eq(QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId),
        org.mockito.ArgumentMatchers.eq("token"),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void terminalTargetIsIgnoredWithoutAcquiringAClaim() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    var listener = new VoiceSynthesisListener(
        handler, claims, mock(TaskRetryPolicy.class));
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, true, 0, 0));

    listener.receive(task, source);

    verify(claims, times(0)).acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void retryableFailureRoutesThroughTheRetryPipelineAndFencesTheAttemptGeneration() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 0));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.synthesize(task)).thenThrow(new SpeechSynthesisRetryableException("down", 2));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.RETRY);

    listener.receive(task, source);

    verify(retryPolicy).routeFailure(task, source);
    verify(claims).release(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId, "token");
    verify(handler, times(0)).markDead(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void retryExhaustionDeadLettersAndMarksDeadWithTheFencedAttemptGeneration() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 0));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.synthesize(task)).thenThrow(new SpeechSynthesisRetryableException("down", 2));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 2)).thenReturn(true);

    listener.receive(task, source);

    verify(handler).markDead(task, 2);
  }

  @Test
  void exhaustedInspectFailureMustTerminalizeOnlyAValidatedCurrentMessage() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(
        handler, mock(ProcessingClaim.class), retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenThrow(new IllegalStateException("database read failed"));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDeadCurrent(task)).thenReturn(true);

    listener.receive(task, source);

    verify(handler).markDeadCurrent(task);
  }

  @Test
  void exhaustedOccupiedClaimMustNotTerminalizeAnotherOwnersGeneration() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 0));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);

    assertThatThrownBy(() -> listener.receive(task, source))
        .isInstanceOf(IllegalStateException.class);

    verify(handler, times(0)).markDead(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void dlqSuccessFollowedByTerminalDatabaseFailureMustEscape() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 0));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.synthesize(task)).thenThrow(new SpeechSynthesisRetryableException("down", 2));
    when(retryPolicy.routeFailure(task, source))
        .thenReturn(TaskRetryPolicy.RouteOutcome.DEAD_LETTER);
    when(handler.markDead(task, 2))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> listener.receive(task, source))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void staleOutcomeReleasesTheClaimWithoutRoutingAFailure() {
    VoiceSynthesisHandler handler = mock(VoiceSynthesisHandler.class);
    ProcessingClaim claims = mock(ProcessingClaim.class);
    TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
    var listener = new VoiceSynthesisListener(handler, claims, retryPolicy);
    UUID speechId = UUID.randomUUID();
    TaskMessage task = new TaskMessage(
        UUID.randomUUID(), AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    Message source = new Message(new byte[0], new MessageProperties());
    when(handler.inspect(task)).thenReturn(
        new VoiceSynthesisHandler.VoiceSynthesisTarget(speechId, false, 1, 1));
    when(claims.acquire(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of("token"));
    when(handler.synthesize(task)).thenReturn(VoiceSynthesisHandler.Outcome.STALE);

    listener.receive(task, source);

    verify(claims).release(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId, "token");
    verify(retryPolicy, times(0)).routeFailure(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }
}
