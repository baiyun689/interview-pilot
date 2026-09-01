package interview.pilot.async.policy;

import java.util.UUID;

import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

/**
 * Shared manual-retry behavior of the two knowledge task types (index and delete): both
 * re-run the indexing pipeline, so both gate on a FAILED document and re-queue it with
 * {@code beginReindex()}. Kept as separate policy beans (one per {@link
 * AsyncTaskType}) so the two types can diverge later without touching shared code.
 */
public abstract class AbstractKnowledgeDocumentRetryPolicy extends AbstractRetryableTaskPolicy {
  private static final String BIZ_KEY_PREFIX = "knowledge-document:";
  /**
   * Public so {@code KnowledgeIndexListener} acquires the very key this policy clears on
   * manual retry — a drifted literal on either side would silently break retry idempotency.
   */
  public static final String CLAIM_KEY_PREFIX = "knowledge-index:";

  private final KnowledgeDocumentRepository knowledgeDocuments;

  protected AbstractKnowledgeDocumentRetryPolicy(KnowledgeDocumentRepository knowledgeDocuments) {
    this.knowledgeDocuments = knowledgeDocuments;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return CLAIM_KEY_PREFIX + parseKnowledgeDocumentId(task.getBizKey());
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    UUID documentId = parseKnowledgeDocumentId(task.getBizKey());
    KnowledgeDocumentEntity document = knowledgeDocuments.findByDocumentId(documentId)
        .orElseThrow(AbstractRetryableTaskPolicy::stateInvalid);
    if (document.getStatus() != KnowledgeDocumentStatus.FAILED) {
      throw stateInvalid();
    }
    document.beginReindex();
  }

  private UUID parseKnowledgeDocumentId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith(BIZ_KEY_PREFIX)) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(bizKey.substring(BIZ_KEY_PREFIX.length()));
    } catch (IllegalArgumentException exception) {
      throw stateInvalid();
    }
  }
}
