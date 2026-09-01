package interview.pilot.async.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.async.policy.RetryableTaskPolicyRegistry;
import interview.pilot.async.policy.VoiceTranscriptionRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;

/**
 * Task 4's minimal routing for VOICE_TRANSCRIPTION: the plan §12 pipeline naming is
 * declared, and the generic task retry endpoint refuses to reset voice tasks — recording
 * retry is owned by VoiceAnswerModule (V9 fenced-epoch lockstep). Task R owns the policy
 * registry refactor.
 */
class VoiceAsyncRoutingTest {

  @Test
  void voiceTranscriptionRoutesToTheDedicatedDurablePipeline() {
    var route = RabbitTopologyConfig.routeFor(AsyncTaskType.VOICE_TRANSCRIPTION);

    assertThat(route.mainExchange()).isEqualTo("interview-pilot.voice.transcription");
    assertThat(route.mainQueue()).isEqualTo("interview-pilot.voice.transcription.main");
    assertThat(route.mainRoutingKey()).isEqualTo("voice.transcription");
    assertThat(route.deadLetterExchange())
        .isEqualTo("interview-pilot.voice.transcription.dead-letter");
    assertThat(route.deadLetterQueue()).isEqualTo("interview-pilot.voice.transcription.dlq");
    assertThat(route.deadLetterRoutingKey()).isEqualTo("voice.transcription.dead");
  }

  @Test
  void questionSpeechSynthesisRoutesToItsOwnDurablePipeline() {
    // Task 7: the synthesis pipeline mirrors the transcription one (durable exchange/queue,
    // 5s/30s/120s delayed retries via the shared TTL retry queues, DLQ).
    var route = RabbitTopologyConfig.routeFor(AsyncTaskType.QUESTION_SPEECH_SYNTHESIS);

    assertThat(route.mainExchange()).isEqualTo("interview-pilot.voice.synthesis");
    assertThat(route.mainQueue()).isEqualTo("interview-pilot.voice.synthesis.main");
    assertThat(route.mainRoutingKey()).isEqualTo("voice.synthesis");
    assertThat(route.deadLetterExchange())
        .isEqualTo("interview-pilot.voice.synthesis.dead-letter");
    assertThat(route.deadLetterQueue()).isEqualTo("interview-pilot.voice.synthesis.dlq");
    assertThat(route.deadLetterRoutingKey()).isEqualTo("voice.synthesis.dead");
  }

  @Test
  void genericTaskRetryRefusesVoiceTranscriptionTasks() {
    UUID taskId = UUID.randomUUID();
    UUID recordingId = UUID.randomUUID();
    AsyncTaskEntity failed = AsyncTaskEntity.pending(
        1L, AsyncTaskType.VOICE_TRANSCRIPTION,
        VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recordingId, "{}");
    failed.setId(7L);
    failed.setTaskId(taskId);
    failed.setStatus(AsyncTaskStatus.FAILED);
    failed.setVersion(3L);
    var tasks = mock(AsyncTaskRepository.class);
    when(tasks.findByTaskIdAndUserAccountId(taskId, 1L)).thenReturn(Optional.of(failed));
    when(tasks.findByIdAndUserAccountId(7L, 1L)).thenReturn(Optional.of(failed));
    var claims = mock(ProcessingClaim.class);
    when(claims.clearTerminal(VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recordingId))
        .thenReturn(ProcessingClaim.ClearResult.ABSENT);
    var transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    var registry = new RetryableTaskPolicyRegistry(List.of(new VoiceTranscriptionRetryPolicy()));
    var service = new AsyncTaskService(
        tasks, claims, transactionManager, mock(AiMetrics.class), registry);

    assertThatThrownBy(() -> service.retry(
        new CurrentUser(1L, new UUID(0L, 1L), "owner@example.com", "Owner"), taskId,
        UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE"));
  }

  @Test
  void genericTaskRetryRefusesQuestionSpeechSynthesisTasks() {
    // Task 7: the speech task's retry belongs to Task 8's speech retry endpoint (epoch
    // lockstep with the question_speech row) — never the generic task retry endpoint.
    UUID taskId = UUID.randomUUID();
    UUID speechId = UUID.randomUUID();
    AsyncTaskEntity failed = AsyncTaskEntity.pending(
        1L, AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId, "{}");
    failed.setId(7L);
    failed.setTaskId(taskId);
    failed.setStatus(AsyncTaskStatus.FAILED);
    failed.setVersion(3L);
    var tasks = mock(AsyncTaskRepository.class);
    when(tasks.findByTaskIdAndUserAccountId(taskId, 1L)).thenReturn(Optional.of(failed));
    when(tasks.findByIdAndUserAccountId(7L, 1L)).thenReturn(Optional.of(failed));
    var claims = mock(ProcessingClaim.class);
    when(claims.clearTerminal(QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId))
        .thenReturn(ProcessingClaim.ClearResult.ABSENT);
    var transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    var registry = new RetryableTaskPolicyRegistry(
        List.of(new QuestionSpeechSynthesisRetryPolicy()));
    var service = new AsyncTaskService(
        tasks, claims, transactionManager, mock(AiMetrics.class), registry);

    assertThatThrownBy(() -> service.retry(
        new CurrentUser(1L, new UUID(0L, 1L), "owner@example.com", "Owner"), taskId,
        UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE"));
  }
}
