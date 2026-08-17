package interview.pilot.knowledge.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import tools.jackson.databind.ObjectMapper;

class KnowledgeRevisionCleanupTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void keepsRevisionReferencedByActiveInterview() throws Exception {
    UUID documentId = UUID.randomUUID();
    VectorStore store = mock(VectorStore.class);
    InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    InterviewSessionEntity session = mock(InterviewSessionEntity.class);
    when(session.getStatus()).thenReturn(SessionStatus.INTERVIEWING);
    when(session.getKnowledgeScopeSnapshot()).thenReturn(objectMapper.writeValueAsString(
        new ValidatedKnowledgeScope(
            UUID.randomUUID(), List.of(UUID.randomUUID()),
            List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 1)), "embed")));
    when(sessions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(session));

    new KnowledgeRevisionCleanup(
        Optional.of(store), sessions, objectMapper, mock(KnowledgeRevisionCandidates.class))
        .cleanupOlderRevisions(documentId, 2);

    verify(store, never()).delete(any(Filter.Expression.class));
  }

  @Test
  void deletesOnlyExplicitOldRevisionWhenNoActiveInterviewReferencesIt() {
    UUID documentId = UUID.randomUUID();
    VectorStore store = mock(VectorStore.class);
    InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    when(sessions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

    new KnowledgeRevisionCleanup(
        Optional.of(store), sessions, objectMapper, mock(KnowledgeRevisionCandidates.class))
        .cleanupOlderRevisions(documentId, 2);

    verify(store).delete(any(Filter.Expression.class));
  }

  @Test
  void scheduledSweepRevisitsAndRetriesFailedCleanup() {
    UUID documentId = UUID.randomUUID();
    VectorStore store = mock(VectorStore.class);
    InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    KnowledgeRevisionCandidates candidates = mock(KnowledgeRevisionCandidates.class);
    when(candidates.findEligible()).thenReturn(List.of(
        new KnowledgeRevisionCandidates.Candidate(documentId, 2)));
    when(sessions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
    doThrow(new RuntimeException("temporary"))
        .doNothing().when(store).delete(any(Filter.Expression.class));
    var cleanup = new KnowledgeRevisionCleanup(
        Optional.of(store), sessions, objectMapper, candidates);

    cleanup.retryEligibleCleanups();
    cleanup.retryEligibleCleanups();

    verify(store, times(2)).delete(any(Filter.Expression.class));
  }
}
