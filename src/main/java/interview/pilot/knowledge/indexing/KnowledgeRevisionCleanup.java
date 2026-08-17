package interview.pilot.knowledge.indexing;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class KnowledgeRevisionCleanup {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeRevisionCleanup.class);
  private final VectorStore vectorStore;
  private final InterviewSessionRepository sessions;
  private final ObjectMapper objectMapper;
  private final KnowledgeRevisionCandidates candidates;

  public KnowledgeRevisionCleanup(
      Optional<VectorStore> vectorStore, InterviewSessionRepository sessions,
      ObjectMapper objectMapper, KnowledgeRevisionCandidates candidates) {
    this.vectorStore = vectorStore.orElse(null);
    this.sessions = sessions;
    this.objectMapper = objectMapper;
    this.candidates = candidates;
  }

  @Scheduled(
      fixedDelayString = "${app.knowledge.revision-cleanup-interval:PT10M}",
      initialDelayString = "${app.knowledge.revision-cleanup-initial-delay:PT2M}")
  public void retryEligibleCleanups() {
    if (vectorStore == null) return;
    candidates.findEligible().forEach(candidate -> cleanupInactiveRevisions(
        candidate.documentId(), candidate.activeRevision(), candidate.currentRevision()));
  }

  public void cleanupOlderRevisions(UUID documentId, int activeRevision) {
    cleanupInactiveRevisions(documentId, activeRevision, activeRevision);
  }

  public void cleanupInactiveRevisions(
      UUID documentId, int activeRevision, int currentRevision) {
    if (vectorStore == null || currentRevision < 1) return;
    for (int revision = 1; revision <= currentRevision; revision++) {
      if (revision == activeRevision) continue;
      if (hasActiveReference(documentId, revision)) continue;
      try {
        vectorStore.delete(and(
            eq("document_id", documentId.toString()),
            eq("index_revision", String.valueOf(revision))));
      } catch (RuntimeException exception) {
        log.warn("Knowledge revision cleanup deferred document={} revision={} reason={}",
            documentId, revision, exception.getMessage());
      }
    }
  }

  private boolean hasActiveReference(UUID documentId, int revision) {
    return sessions.findAllByOrderByCreatedAtDesc().stream()
        .filter(session -> session.getStatus() == SessionStatus.CREATED
            || session.getStatus() == SessionStatus.INTERVIEWING
            || session.getStatus() == SessionStatus.EVALUATING)
        .anyMatch(session -> references(
            session.getKnowledgeScopeSnapshot(), documentId, revision));
  }

  private boolean references(String snapshot, UUID documentId, int revision) {
    if (snapshot == null || snapshot.isBlank()) return false;
    try {
      ValidatedKnowledgeScope scope = objectMapper.readValue(
          snapshot, ValidatedKnowledgeScope.class);
      return scope.documents().stream().anyMatch(document ->
          document.documentId().equals(documentId) && document.indexRevision() == revision);
    } catch (JacksonException | IllegalArgumentException exception) {
      return true;
    }
  }

  private static Filter.Expression eq(String key, String value) {
    return new Filter.Expression(Filter.ExpressionType.EQ,
        new Filter.Key(key), new Filter.Value(value));
  }

  private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
    return new Filter.Expression(Filter.ExpressionType.AND, left, right);
  }
}
