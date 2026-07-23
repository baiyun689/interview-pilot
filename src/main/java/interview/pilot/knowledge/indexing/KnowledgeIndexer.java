package interview.pilot.knowledge.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;

@Component
public class KnowledgeIndexer {
  private final KnowledgeDocumentRepository documentRepository;
  private final KnowledgeDocumentStore store;
  private final KnowledgeDocumentParser parser;
  private final RecursiveTextSplitter splitter;

  public KnowledgeIndexer(
      KnowledgeDocumentRepository documentRepository,
      KnowledgeDocumentStore store,
      KnowledgeDocumentParser parser,
      RecursiveTextSplitter splitter) {
    this.documentRepository = documentRepository;
    this.store = store;
    this.parser = parser;
    this.splitter = splitter;
  }

  /**
   * Executes parse → split — embedding and Qdrant upsert will be added in a later task.
   * Runs entirely outside a database transaction.
   *
   * @return number of chunks produced, or -1 if the document revision is stale
   */
  public int index(UUID documentUuid, int expectedRevision) {
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(documentUuid)
        .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found"));
    if (document.getIndexRevision() != expectedRevision) {
      return -1;
    }
    if (document.getStatus() != KnowledgeDocumentStatus.PROCESSING) {
      throw new IllegalStateException("Document is not being indexed");
    }

    String parsed;
    try (InputStream input = store.open(document.getStorageKey())) {
      parsed = parser.parse(input, document.getOriginalFilename(), 10L * 1024 * 1024);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Knowledge document could not be opened", exception);
    }
    if (parsed == null || parsed.isBlank()) {
      return 0;
    }

    List<String> chunks = splitter.split(parsed);
    // TODO: Future task — embed chunks and upsert to Qdrant

    return chunks.size();
  }
}
