package interview.pilot.async.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;

class RetryableTaskPolicyRegistryTest {

  private final RetryableTaskPolicyRegistry registry = new RetryableTaskPolicyRegistry(
      List.of(
          new ResumeAnalysisRetryPolicy(mock(ResumeRepository.class)),
          new InterviewPreparationRetryPolicy(mock(InterviewSessionRepository.class)),
          new InterviewEvaluationRetryPolicy(mock(InterviewSessionRepository.class)),
          new KnowledgeDocumentIndexRetryPolicy(mock(KnowledgeDocumentRepository.class)),
          new KnowledgeDocumentDeleteRetryPolicy(mock(KnowledgeDocumentRepository.class)),
          new VoiceTranscriptionRetryPolicy()));

  @Test
  void routesEveryExistingTaskTypeToItsPolicy() {
    assertThat(registry.forType(AsyncTaskType.RESUME_ANALYSIS))
        .isInstanceOf(ResumeAnalysisRetryPolicy.class);
    assertThat(registry.forType(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION))
        .isInstanceOf(InterviewPreparationRetryPolicy.class);
    assertThat(registry.forType(AsyncTaskType.INTERVIEW_EVALUATION))
        .isInstanceOf(InterviewEvaluationRetryPolicy.class);
    assertThat(registry.forType(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX))
        .isInstanceOf(KnowledgeDocumentIndexRetryPolicy.class);
    assertThat(registry.forType(AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE))
        .isInstanceOf(KnowledgeDocumentDeleteRetryPolicy.class);
    assertThat(registry.forType(AsyncTaskType.VOICE_TRANSCRIPTION))
        .isInstanceOf(VoiceTranscriptionRetryPolicy.class);
  }

  @Test
  void everyAsyncTaskTypeConstantHasARegisteredPolicy() {
    // The guard the old implicit else-branch lacked: a new enum constant without a policy
    // must be caught here (Task R), never silently reset as if it were an evaluation.
    assertThat(registry.registeredTypes())
        .containsExactlyInAnyOrder(AsyncTaskType.values());
  }

  @Test
  void anUnregisteredTaskTypeFailsLoudlyInsteadOfDefaulting() {
    var partial = new RetryableTaskPolicyRegistry(List.of(new VoiceTranscriptionRetryPolicy()));
    assertThatThrownBy(() -> partial.forType(AsyncTaskType.RESUME_ANALYSIS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No RetryableTaskPolicy registered for RESUME_ANALYSIS");
  }

  @Test
  void aNullTypeIsRejected() {
    assertThatThrownBy(() -> registry.forType(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void duplicatePolicyTypesAreRejected() {
    var resumePolicy = new ResumeAnalysisRetryPolicy(mock(ResumeRepository.class));
    assertThatThrownBy(() -> new RetryableTaskPolicyRegistry(List.of(
        resumePolicy, resumePolicy)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate RetryableTaskPolicy for RESUME_ANALYSIS");
  }
}
