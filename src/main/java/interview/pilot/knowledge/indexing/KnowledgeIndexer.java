package interview.pilot.knowledge.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;

@Component
public class KnowledgeIndexer {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

  private final KnowledgeDocumentRepository documentRepository;
  private final KnowledgeDocumentStore store;
  private final KnowledgeDocumentParser parser;
  private final RecursiveTextSplitter splitter;
  private final UserAccountRepository userAccountRepository;
  private final VectorStore vectorStore;
  private final String embeddingModel;

  public KnowledgeIndexer(
      KnowledgeDocumentRepository documentRepository,
      KnowledgeDocumentStore store,
      KnowledgeDocumentParser parser,
      RecursiveTextSplitter splitter,
      UserAccountRepository userAccountRepository,
      Optional<VectorStore> vectorStore,
      @Value("${app.knowledge.embedding.model:text-embedding-v3}") String embeddingModel) {
    this.documentRepository = documentRepository;
    this.store = store;
    this.parser = parser;
    this.splitter = splitter;
    this.userAccountRepository = userAccountRepository;
    this.vectorStore = vectorStore.orElse(null);
    this.embeddingModel = embeddingModel;
  }

  /**
   * Executes parse → split → embed → upsert.
   * When the VectorStore bean is available (knowledge enabled), chunks are embedded
   * and upserted into Qdrant. Old vectors for this document are deleted first to
   * support reindexing. Runs entirely outside a database transaction.
   *
   * @return number of chunks produced, or -1 if the document revision is stale
   */
  public int index(UUID documentUuid, int expectedRevision) {
    if (vectorStore == null) {
      throw new IllegalStateException("Knowledge vector store is unavailable");
    }
    KnowledgeDocumentEntity document = documentRepository
        .findByDocumentIdWithKnowledgeBase(documentUuid)
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
      document.setParsedText("");
      document.setEmbeddingSnapshot(null);
      documentRepository.save(document);
      return 0;
    }

    List<String> chunks = splitter.split(parsed);
    if (vectorStore != null && !chunks.isEmpty()) {
      embedAndUpsert(document, chunks);
    }

    document.setParsedText(parsed);
    document.setEmbeddingSnapshot(
        vectorStore != null ? embeddingSnapshot() : null);
    documentRepository.save(document);
    return chunks.size();
  }

  private void embedAndUpsert(KnowledgeDocumentEntity document, List<String> chunks) {
    KnowledgeBaseEntity kb = document.getKnowledgeBase();
    UUID userId = userAccountRepository.findById(kb.getUserAccountId())
        .orElseThrow(() -> new IllegalArgumentException(
            "User account " + kb.getUserAccountId() + " not found"))
        .getUserId();
    String kbId = kb.getKnowledgeBaseId().toString();
    String docId = document.getDocumentId().toString();
    String filename = document.getOriginalFilename();
    String revision = String.valueOf(document.getIndexRevision());

    // Delete old vectors for this document before re-adding (supports reindex)
    try {
      vectorStore.delete(new Filter.Expression(Filter.ExpressionType.EQ,
          new Filter.Key("document_id"), new Filter.Value(docId)));
    } catch (RuntimeException exception) {
      log.warn("Failed to delete old vectors for document {}: {}", docId, exception.getMessage());
    }

    List<org.springframework.ai.document.Document> docs = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("user_id", userId.toString());
      metadata.put("knowledge_base_id", kbId);
      metadata.put("document_id", docId);
      metadata.put("filename", filename);
      metadata.put("chunk_index", String.valueOf(i));
      metadata.put("index_revision", revision);
      docs.add(new org.springframework.ai.document.Document(chunks.get(i), metadata));
    }

    vectorStore.add(docs);
    log.info("Indexed {} chunks for document {} (user={}, kb={})",
        chunks.size(), docId, userId, kbId);
  }

  private String embeddingSnapshot() {
    return "{\"model\":\"" + embeddingModel + "\",\"dimensions\":1024}";
  }
}
