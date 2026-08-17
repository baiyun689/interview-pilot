package interview.pilot.interview.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.application.InterviewCreation;
import interview.pilot.interview.application.InterviewResponseMapper;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.ObjectMapper;

class JpaInterviewCreationStoreTest {

  @Test
  void rejectsScopeWhoseActiveRevisionChangedBeforePersistence() {
    UUID baseId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    KnowledgeDocumentEntity changed = mock(KnowledgeDocumentEntity.class);
    when(changed.getDocumentId()).thenReturn(documentId);
    when(changed.getActiveIndexRevision()).thenReturn(2);
    when(documents.lockReadyByKnowledgeBaseIdsAndUserAccountId(List.of(baseId), 42L))
        .thenReturn(List.of(changed));
    var store = new JpaInterviewCreationStore(
        mock(ResumeRepository.class), mock(JobProfileRepository.class),
        mock(InterviewSessionRepository.class), mock(InterviewTurnRepository.class),
        mock(KnowledgeBaseRepository.class), documents,
        mock(InterviewKnowledgeBaseRepository.class), new ObjectMapper(),
        mock(InterviewResponseMapper.class));
    var scope = new ValidatedKnowledgeScope(
        UUID.randomUUID(), List.of(baseId),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 1)), "embed");
    var creation = new InterviewCreation(
        42L, null, "Backend", "JD", Difficulty.MEDIUM, 5,
        "provider", "model", null, null, null, null,
        scope, RagContextSnapshot.notConfigured(), null);

    assertThatThrownBy(() -> store.create(creation))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo("KNOWLEDGE_SCOPE_STALE");
  }
}
