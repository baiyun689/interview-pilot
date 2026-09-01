package interview.pilot.async.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.common.exception.BusinessException;
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
 * Direct contract of the extracted policies (Task R): claim key formats, parse failures, and
 * reset semantics per task type, mirroring what the centralized switch in
 * {@code AsyncTaskService} produced before the registry refactor.
 */
class RetryableTaskPolicyTest {

  private static AsyncTaskEntity task(AsyncTaskType type, String bizKey) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(1L, type, bizKey, "{}");
    task.setStatus(AsyncTaskStatus.FAILED);
    return task;
  }

  @Test
  void resumeAnalysisClaimKeyUsesResumeAnalysisPrefixWithTheResumeId() {
    var policy = new ResumeAnalysisRetryPolicy(mock(ResumeRepository.class));

    assertThat(policy.claimKey(task(AsyncTaskType.RESUME_ANALYSIS, "resume:42")))
        .isEqualTo("resume-analysis:42");
  }

  @Test
  void interviewPreparationClaimKeyUsesPreparationPrefixWithTheSessionId() {
    var policy = new InterviewPreparationRetryPolicy(mock(InterviewSessionRepository.class));
    UUID sessionId = UUID.randomUUID();

    assertThat(policy.claimKey(
        task(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + sessionId)))
        .isEqualTo("interview-preparation:" + sessionId);
  }

  @Test
  void interviewEvaluationClaimKeyUsesReportPrefixWithTheSessionId() {
    var policy = new InterviewEvaluationRetryPolicy(mock(InterviewSessionRepository.class));
    UUID sessionId = UUID.randomUUID();

    assertThat(policy.claimKey(task(AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId)))
        .isEqualTo("interview-report:" + sessionId);
  }

  @Test
  void knowledgeDocumentClaimKeyUsesIndexPrefixWithTheDocumentIdForBothTypes() {
    var indexPolicy = new KnowledgeDocumentIndexRetryPolicy(mock(KnowledgeDocumentRepository.class));
    var deletePolicy = new KnowledgeDocumentDeleteRetryPolicy(mock(KnowledgeDocumentRepository.class));
    UUID documentId = UUID.randomUUID();

    assertThat(indexPolicy.claimKey(
        task(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "knowledge-document:" + documentId)))
        .isEqualTo("knowledge-index:" + documentId);
    assertThat(deletePolicy.claimKey(
        task(AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE, "knowledge-document:" + documentId)))
        .isEqualTo("knowledge-index:" + documentId);
  }

  @Test
  void voiceClaimKeyMatchesTheTaskBizKeyFormatOwnedByThePolicy() {
    var policy = new VoiceTranscriptionRetryPolicy();
    UUID recordingId = UUID.randomUUID();

    assertThat(policy.claimKey(task(
        AsyncTaskType.VOICE_TRANSCRIPTION, VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recordingId)))
        .isEqualTo(VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX + recordingId);
  }

  @Test
  void malformedBizKeysFailWithTaskStateInvalidForEveryPolicy() {
    var resumePolicy = new ResumeAnalysisRetryPolicy(mock(ResumeRepository.class));
    var preparationPolicy = new InterviewPreparationRetryPolicy(mock(InterviewSessionRepository.class));
    var evaluationPolicy = new InterviewEvaluationRetryPolicy(mock(InterviewSessionRepository.class));
    var indexPolicy = new KnowledgeDocumentIndexRetryPolicy(mock(KnowledgeDocumentRepository.class));
    var voicePolicy = new VoiceTranscriptionRetryPolicy();

    expectTaskStateInvalid(() ->
        resumePolicy.claimKey(task(AsyncTaskType.RESUME_ANALYSIS, "resume:not-a-number")));
    expectTaskStateInvalid(() ->
        preparationPolicy.claimKey(task(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "x:y")));
    expectTaskStateInvalid(() ->
        evaluationPolicy.claimKey(task(AsyncTaskType.INTERVIEW_EVALUATION, "interview:not-a-uuid")));
    expectTaskStateInvalid(() ->
        indexPolicy.claimKey(task(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "doc:1")));
    expectTaskStateInvalid(() ->
        voicePolicy.claimKey(task(AsyncTaskType.VOICE_TRANSCRIPTION, "recording:1")));
  }

  @Test
  void resumeResetRequiresFailedRowAndRestoresItToPending() {
    var repository = mock(ResumeRepository.class);
    var policy = new ResumeAnalysisRetryPolicy(repository);
    var resume = mock(ResumeEntity.class);
    when(resume.getStatus()).thenReturn(ResumeStatus.FAILED);
    when(repository.findByIdAndUserAccountId(42L, 1L)).thenReturn(Optional.of(resume));

    policy.reset(task(AsyncTaskType.RESUME_ANALYSIS, "resume:42"), 1L);

    verify(resume).setStatus(ResumeStatus.PENDING);
    verify(resume).setFailureReason(null);
    verify(resume).setSkillsSnapshot(null);
    verify(resume).setEvaluationSnapshot(null);
  }

  @Test
  void preparationResetRequiresPreparationFailedAndRetriesPreparation() {
    var repository = mock(InterviewSessionRepository.class);
    var policy = new InterviewPreparationRetryPolicy(repository);
    UUID sessionId = UUID.randomUUID();
    var session = mock(InterviewSessionEntity.class);
    when(session.getStatus()).thenReturn(SessionStatus.PREPARATION_FAILED);
    when(repository.findBySessionIdAndUserAccountId(sessionId, 1L))
        .thenReturn(Optional.of(session));

    policy.reset(task(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + sessionId), 1L);

    verify(session).retryPreparation();
  }

  @Test
  void evaluationResetRequiresEvaluationFailedAndRetriesEvaluation() {
    var repository = mock(InterviewSessionRepository.class);
    var policy = new InterviewEvaluationRetryPolicy(repository);
    UUID sessionId = UUID.randomUUID();
    var session = mock(InterviewSessionEntity.class);
    when(session.getStatus()).thenReturn(SessionStatus.EVALUATION_FAILED);
    when(repository.findBySessionIdAndUserAccountId(sessionId, 1L))
        .thenReturn(Optional.of(session));

    policy.reset(task(AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId), 1L);

    verify(session).retryEvaluation();
  }

  @Test
  void knowledgeResetRequiresFailedRowAndReindexes() {
    var repository = mock(KnowledgeDocumentRepository.class);
    var policy = new KnowledgeDocumentIndexRetryPolicy(repository);
    UUID documentId = UUID.randomUUID();
    var document = mock(KnowledgeDocumentEntity.class);
    when(document.getStatus()).thenReturn(KnowledgeDocumentStatus.FAILED);
    when(repository.findByDocumentId(documentId)).thenReturn(Optional.of(document));

    policy.reset(task(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "knowledge-document:" + documentId), 1L);

    verify(document).beginReindex();
  }

  @Test
  void voiceResetRefusesGenericRetryWithTheStableError() {
    var policy = new VoiceTranscriptionRetryPolicy();

    assertThatThrownBy(() -> policy.reset(
        task(AsyncTaskType.VOICE_TRANSCRIPTION, "voice-recording:" + UUID.randomUUID()), 1L))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo("TASK_NOT_RETRYABLE");
          assertThat(error.getMessage())
              .isEqualTo("Voice transcription retry is managed by the recording");
        });
  }

  @Test
  void wrongBusinessStateFailsWithTaskStateInvalid() {
    var repository = mock(ResumeRepository.class);
    var policy = new ResumeAnalysisRetryPolicy(repository);
    var resume = mock(ResumeEntity.class);
    when(resume.getStatus()).thenReturn(ResumeStatus.PENDING);
    when(repository.findByIdAndUserAccountId(42L, 1L)).thenReturn(Optional.of(resume));

    expectTaskStateInvalid(() -> policy.reset(task(AsyncTaskType.RESUME_ANALYSIS, "resume:42"), 1L));
  }

  @Test
  void missingBusinessRowFailsWithTaskStateInvalid() {
    var policy = new ResumeAnalysisRetryPolicy(mock(ResumeRepository.class));

    expectTaskStateInvalid(() -> policy.reset(task(AsyncTaskType.RESUME_ANALYSIS, "resume:42"), 1L));
  }

  @Test
  void voiceBizKeyPrefixIsTheSingleSourceForTheTaskFormat() {
    // Pinned so the constant value cannot drift from the format VoiceAnswerServiceImpl
    // writes (Task 4 review M4).
    assertThat(VoiceTranscriptionRetryPolicy.BIZ_KEY_PREFIX).isEqualTo("voice-recording:");
  }

  private static void expectTaskStateInvalid(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
        error -> assertThat(error.code()).isEqualTo("TASK_STATE_INVALID"));
  }
}
