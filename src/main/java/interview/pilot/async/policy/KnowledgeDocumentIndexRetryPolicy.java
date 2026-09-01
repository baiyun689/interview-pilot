package interview.pilot.async.policy;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

/**
 * Manual retry for KNOWLEDGE_DOCUMENT_INDEX ({@link AbstractKnowledgeDocumentRetryPolicy}).
 */
@Component
public class KnowledgeDocumentIndexRetryPolicy extends AbstractKnowledgeDocumentRetryPolicy {
  public KnowledgeDocumentIndexRetryPolicy(KnowledgeDocumentRepository knowledgeDocuments) {
    super(knowledgeDocuments);
  }

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX;
  }
}
