package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.UUID;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

import org.springframework.transaction.annotation.Transactional;

class KnowledgeChunkRepositoryAdapter implements KnowledgeChunkRepository {
  private final KnowledgeChunkJpaRepository delegate;

  KnowledgeChunkRepositoryAdapter(KnowledgeChunkJpaRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeChunkEntity> search(
      ValidatedKnowledgeScope scope, String query, int candidateCount) {
    return delegate.search(scope, query, candidateCount);
  }

  @Override
  @Transactional
  public void replaceRevision(
      UUID documentId, int indexRevision, List<KnowledgeChunkEntity> chunks) {
    delegate.deleteByDocumentIdAndIndexRevision(documentId, indexRevision);
    if (!chunks.isEmpty()) {
      delegate.saveAll(chunks);
    }
  }

  @Override
  @Transactional
  public void deleteByDocumentIdAndIndexRevision(UUID documentId, int indexRevision) {
    delegate.deleteByDocumentIdAndIndexRevision(documentId, indexRevision);
  }

  @Override
  @Transactional
  public void deleteByDocumentId(UUID documentId) {
    delegate.deleteByDocumentId(documentId);
  }
}
