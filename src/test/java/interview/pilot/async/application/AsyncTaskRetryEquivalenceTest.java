package interview.pilot.async.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.InterviewEvaluationRetryPolicy;
import interview.pilot.async.policy.InterviewPreparationRetryPolicy;
import interview.pilot.async.policy.KnowledgeDocumentDeleteRetryPolicy;
import interview.pilot.async.policy.KnowledgeDocumentIndexRetryPolicy;
import interview.pilot.async.policy.ResumeAnalysisRetryPolicy;
import interview.pilot.async.policy.RetryableTaskPolicyRegistry;
import interview.pilot.async.policy.VoiceTranscriptionRetryPolicy;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

/**
 * Behavior-equivalence characterization of the task-type-specific manual retry semantics
 * (Task R). Every test pins a behavior of the current AsyncTaskService that the
 * RetryableTaskPolicy registry refactor must preserve exactly: claim key per type, retry
 * refusal per type, and per-type state recovery. These tests pass before AND after the
 * refactor (only the service construction in {@link #service()} is adjusted).
 */
class AsyncTaskRetryEquivalenceTest {
  private static final CurrentUser OWNER = new CurrentUser(
      1L, new UUID(0L, 1L), "owner@example.com", "Owner");

  private AsyncTaskRepository tasks;
  private ResumeRepository resumes;
  private InterviewSessionRepository sessions;
  private KnowledgeDocumentRepository knowledgeDocuments;
  private ProcessingClaim claims;
  private PlatformTransactionManager transactionManager;
  private AiMetrics metrics;

  @BeforeEach
  void setUp() {
    tasks = mock(AsyncTaskRepository.class);
    resumes = mock(ResumeRepository.class);
    sessions = mock(InterviewSessionRepository.class);
    knowledgeDocuments = mock(KnowledgeDocumentRepository.class);
    claims = mock(ProcessingClaim.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    metrics = mock(AiMetrics.class);
  }

  private AsyncTaskService service() {
    var registry = new RetryableTaskPolicyRegistry(List.of(
        new ResumeAnalysisRetryPolicy(resumes),
        new InterviewPreparationRetryPolicy(sessions),
        new InterviewEvaluationRetryPolicy(sessions),
        new KnowledgeDocumentIndexRetryPolicy(knowledgeDocuments),
        new KnowledgeDocumentDeleteRetryPolicy(knowledgeDocuments),
        new VoiceTranscriptionRetryPolicy()));
    return new AsyncTaskService(tasks, claims, transactionManager, metrics, registry);
  }

  private AsyncTaskEntity task(AsyncTaskType type, String bizKey, AsyncTaskStatus status) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(1L, type, bizKey, "{}");
    task.setId(7L);
    task.setTaskId(UUID.randomUUID());
    task.setStatus(status);
    task.setVersion(3L);
    return task;
  }

  private void wire(AsyncTaskEntity task) {
    when(tasks.findByTaskIdAndUserAccountId(task.getTaskId(), 1L)).thenReturn(Optional.of(task));
    when(tasks.findByIdAndUserAccountId(7L, 1L)).thenReturn(Optional.of(task));
    when(claims.clearTerminal(any())).thenReturn(ProcessingClaim.ClearResult.ABSENT);
    when(tasks.saveAndFlush(any(AsyncTaskEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private BusinessException retryError(AsyncTaskService service, UUID taskId) {
    try {
      service.retry(OWNER, taskId, UUID.randomUUID());
      throw new AssertionError("retry should have refused the task");
    } catch (BusinessException exception) {
      return exception;
    }
  }

  @Test
  void resumeAnalysisRetryClearsTheExactClaimAndRestoresTheRow() {
    long resumeId = 42L;
    var task = task(AsyncTaskType.RESUME_ANALYSIS, "resume:" + resumeId, AsyncTaskStatus.FAILED);
    var resume = mock(ResumeEntity.class);
    when(resume.getStatus()).thenReturn(ResumeStatus.FAILED);
    when(resumes.findByIdAndUserAccountId(resumeId, 1L)).thenReturn(Optional.of(resume));
    wire(task);

    var response = service().retry(OWNER, task.getTaskId(), UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AsyncTaskStatus.PENDING);
    verify(claims).clearTerminal("resume-analysis:" + resumeId);
    verify(resume).setStatus(ResumeStatus.PENDING);
    verify(resume).setFailureReason(null);
    verify(resume).setSkillsSnapshot(null);
    verify(resume).setEvaluationSnapshot(null);
  }

  @Test
  void interviewPreparationRetryClearsTheExactClaimAndRetriesPreparation() {
    UUID sessionId = UUID.randomUUID();
    var task = task(
        AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + sessionId,
        AsyncTaskStatus.FAILED);
    var session = mock(InterviewSessionEntity.class);
    when(session.getStatus()).thenReturn(SessionStatus.PREPARATION_FAILED);
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L))
        .thenReturn(Optional.of(session));
    wire(task);

    var response = service().retry(OWNER, task.getTaskId(), UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AsyncTaskStatus.PENDING);
    verify(claims).clearTerminal("interview-preparation:" + sessionId);
    verify(session).retryPreparation();
    verify(session, never()).retryEvaluation();
  }

  @Test
  void interviewEvaluationRetryClearsTheExactClaimAndRetriesEvaluation() {
    UUID sessionId = UUID.randomUUID();
    var task = task(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, AsyncTaskStatus.FAILED);
    var session = mock(InterviewSessionEntity.class);
    when(session.getStatus()).thenReturn(SessionStatus.EVALUATION_FAILED);
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L))
        .thenReturn(Optional.of(session));
    wire(task);

    var response = service().retry(OWNER, task.getTaskId(), UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AsyncTaskStatus.PENDING);
    verify(claims).clearTerminal("interview-report:" + sessionId);
    verify(session).retryEvaluation();
    verify(session, never()).retryPreparation();
  }

  @ParameterizedTest
  @MethodSource("knowledgeDocumentTypes")
  void knowledgeDocumentRetryClearsTheExactClaimAndReindexes(AsyncTaskType type) {
    UUID documentId = UUID.randomUUID();
    var task = task(type, "knowledge-document:" + documentId, AsyncTaskStatus.FAILED);
    var document = mock(KnowledgeDocumentEntity.class);
    when(document.getStatus()).thenReturn(KnowledgeDocumentStatus.FAILED);
    when(knowledgeDocuments.findByDocumentId(documentId)).thenReturn(Optional.of(document));
    wire(task);

    var response = service().retry(OWNER, task.getTaskId(), UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AsyncTaskStatus.PENDING);
    verify(claims).clearTerminal("knowledge-index:" + documentId);
    verify(document).beginReindex();
  }

  @Test
  void voiceTranscriptionRetryClearsTheClaimButRefusesToResetTheTask() {
    UUID recordingId = UUID.randomUUID();
    var task = task(
        AsyncTaskType.VOICE_TRANSCRIPTION, "voice-recording:" + recordingId,
        AsyncTaskStatus.FAILED);
    wire(task);

    var error = retryError(service(), task.getTaskId());

    // Task 4: the voice claim key still participates in the retry protocol (it is cleared),
    // but the generic endpoint refuses to reset the task — recording retry is owned by
    // VoiceAnswerModule so the recording row and task row stay in epoch lockstep.
    verify(claims).clearTerminal("voice-recording:" + recordingId);
    assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE");
    assertThat(error.getMessage())
        .isEqualTo("Voice transcription retry is managed by the recording");
  }

  @Test
  void deadTaskIsRetryableLikeFailed() {
    var task = task(AsyncTaskType.RESUME_ANALYSIS, "resume:42", AsyncTaskStatus.DEAD);
    var resume = mock(ResumeEntity.class);
    when(resume.getStatus()).thenReturn(ResumeStatus.FAILED);
    when(resumes.findByIdAndUserAccountId(42L, 1L)).thenReturn(Optional.of(resume));
    wire(task);

    var response = service().retry(OWNER, task.getTaskId(), UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AsyncTaskStatus.PENDING);
  }

  @ParameterizedTest
  @MethodSource("malformedBizKeys")
  void malformedBizKeyFailsWithTaskStateInvalidWithoutTouchingTheClaim(
      AsyncTaskType type, String bizKey) {
    var task = task(type, bizKey, AsyncTaskStatus.FAILED);
    wire(task);

    var error = retryError(service(), task.getTaskId());

    assertThat(error.code()).isEqualTo("TASK_STATE_INVALID");
    verify(claims, never()).clearTerminal(any());
  }

  @ParameterizedTest
  @MethodSource("missingBusinessRows")
  void missingBusinessRowFailsWithTaskStateInvalidAfterClearingTheClaim(
      AsyncTaskType type, String bizKey) {
    var task = task(type, bizKey, AsyncTaskStatus.FAILED);
    wire(task);

    var error = retryError(service(), task.getTaskId());

    assertThat(error.code()).isEqualTo("TASK_STATE_INVALID");
    verify(claims).clearTerminal(any());
  }

  @ParameterizedTest
  @MethodSource("wrongBusinessStates")
  void wrongBusinessStateFailsWithTaskStateInvalidAfterClearingTheClaim(
      AsyncTaskType type, String bizKey, String claimKey) {
    var task = task(type, bizKey, AsyncTaskStatus.FAILED);
    wire(task);
    if (type == AsyncTaskType.RESUME_ANALYSIS) {
      var resume = mock(ResumeEntity.class);
      when(resume.getStatus()).thenReturn(ResumeStatus.PENDING);
      when(resumes.findByIdAndUserAccountId(42L, 1L)).thenReturn(Optional.of(resume));
    } else if (type == AsyncTaskType.INTERVIEW_QUESTION_PREPARATION
        || type == AsyncTaskType.INTERVIEW_EVALUATION) {
      var session = mock(InterviewSessionEntity.class);
      when(session.getStatus()).thenReturn(SessionStatus.INTERVIEWING);
      when(sessions.findBySessionIdAndUserAccountId(any(UUID.class), any(Long.class)))
          .thenReturn(Optional.of(session));
    } else {
      var document = mock(KnowledgeDocumentEntity.class);
      when(document.getStatus()).thenReturn(KnowledgeDocumentStatus.PENDING);
      when(knowledgeDocuments.findByDocumentId(any(UUID.class))).thenReturn(Optional.of(document));
    }

    var error = retryError(service(), task.getTaskId());

    assertThat(error.code()).isEqualTo("TASK_STATE_INVALID");
    // The claim is cleared before the business-state check refuses the reset — if this
    // drifted from the listener-side key, manual retry would clear a claim nobody holds.
    verify(claims).clearTerminal(claimKey);
  }

  @Test
  void nonFailedOrDeadTaskIsRefusedBeforeAnyClaimOrStateOperation() {
    var task = task(AsyncTaskType.RESUME_ANALYSIS, "resume:42", AsyncTaskStatus.PENDING);
    wire(task);

    var error = retryError(service(), task.getTaskId());

    assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE");
    assertThat(error.getMessage()).isEqualTo("Only failed or dead tasks can be retried");
    verify(claims, never()).clearTerminal(any());
    verifyNoInteractions(resumes);
  }

  private static Stream<AsyncTaskType> knowledgeDocumentTypes() {
    return Stream.of(
        AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE);
  }

  private static Stream<Arguments> malformedBizKeys() {
    return Stream.of(
        Arguments.of(AsyncTaskType.RESUME_ANALYSIS, "resume:not-a-number"),
        Arguments.of(AsyncTaskType.RESUME_ANALYSIS, "cv:42"),
        Arguments.of(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:not-a-uuid"),
        Arguments.of(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "session:123"),
        Arguments.of(AsyncTaskType.INTERVIEW_EVALUATION, "interview:not-a-uuid"),
        Arguments.of(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "knowledge-document:not-a-uuid"),
        Arguments.of(AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE, "doc:123"),
        Arguments.of(AsyncTaskType.VOICE_TRANSCRIPTION, "voice-recording:not-a-uuid"),
        Arguments.of(AsyncTaskType.VOICE_TRANSCRIPTION, "recording:123"));
  }

  private static Stream<Arguments> missingBusinessRows() {
    return Stream.of(
        Arguments.of(AsyncTaskType.RESUME_ANALYSIS, "resume:42"),
        Arguments.of(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + UUID.randomUUID()),
        Arguments.of(AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + UUID.randomUUID()),
        Arguments.of(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "knowledge-document:" + UUID.randomUUID()),
        Arguments.of(AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE, "knowledge-document:" + UUID.randomUUID()));
  }

  private static Stream<Arguments> wrongBusinessStates() {
    UUID preparationSession = UUID.randomUUID();
    UUID evaluationSession = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    return Stream.of(
        Arguments.of(AsyncTaskType.RESUME_ANALYSIS, "resume:42", "resume-analysis:42"),
        Arguments.of(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
            "interview:" + preparationSession, "interview-preparation:" + preparationSession),
        Arguments.of(AsyncTaskType.INTERVIEW_EVALUATION,
            "interview:" + evaluationSession, "interview-report:" + evaluationSession),
        Arguments.of(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX,
            "knowledge-document:" + documentId, "knowledge-index:" + documentId));
  }
}
