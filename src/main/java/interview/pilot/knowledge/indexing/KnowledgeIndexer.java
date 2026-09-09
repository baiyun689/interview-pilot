package interview.pilot.knowledge.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeChunkEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeChunkRepository;
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
  private final KnowledgeChunkRepository chunkRepository;
  private final String embeddingModel;

  public KnowledgeIndexer(
      KnowledgeDocumentRepository documentRepository,
      KnowledgeDocumentStore store,
      KnowledgeDocumentParser parser,
      RecursiveTextSplitter splitter,
      UserAccountRepository userAccountRepository,
      Optional<VectorStore> vectorStore,
      KnowledgeChunkRepository chunkRepository,
      @Value("${app.knowledge.embedding.model:text-embedding-v3}") String embeddingModel) {
    this.documentRepository = documentRepository;
    this.store = store;
    this.parser = parser;
    this.splitter = splitter;
    this.userAccountRepository = userAccountRepository;
    this.vectorStore = vectorStore.orElse(null);
    this.chunkRepository = chunkRepository;
    this.embeddingModel = embeddingModel;
  }

  /**
   * Executes parse → split → embed → upsert.
   * When the VectorStore bean is available (knowledge enabled), chunks are embedded
   * and deterministically upserted into Qdrant. Previous revisions remain available
   * until revision-aware cleanup proves no active interview references them.
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
    KnowledgeDocumentEntity current = current(documentUuid, expectedRevision);
    if (current == null) return -1;
    if (!chunks.isEmpty()) {
      embedAndUpsert(current, chunks, expectedRevision);
    }

    current = current(documentUuid, expectedRevision);
    if (current == null) return -1;
    current.setParsedText(parsed);
    current.setEmbeddingSnapshot(embeddingSnapshot());
    documentRepository.save(current);
    return chunks.size();
  }

  private void embedAndUpsert(
      KnowledgeDocumentEntity document, List<String> chunks, int expectedRevision) {
    KnowledgeBaseEntity kb = document.getKnowledgeBase();
    UUID userId = kb.getOrganizationId() != null ? null : userAccountRepository.findById(kb.getUserAccountId())
        .orElseThrow(() -> new IllegalArgumentException(
            "User account " + kb.getUserAccountId() + " not found"))
        .getUserId();
    UUID kbUuid = kb.getKnowledgeBaseId();
    String kbId = kbUuid.toString();
    UUID docUuid = document.getDocumentId();
    String docId = docUuid.toString();
    String filename = document.getOriginalFilename();
    String revision = String.valueOf(expectedRevision);

    List<org.springframework.ai.document.Document> docs = new ArrayList<>(chunks.size());
    List<KnowledgeChunkEntity> storedChunks = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      Map<String, Object> metadata = new HashMap<>();
      if (kb.getOrganizationId() == null) metadata.put("user_id", userId.toString());
      else metadata.put("organization_id", kb.getOrganizationId().toString());
      metadata.put("knowledge_base_id", kbId);
      metadata.put("document_id", docId);
      metadata.put("filename", filename);
      metadata.put("chunk_index", String.valueOf(i));
      metadata.put("index_revision", revision);
      metadata.put("section", "chunk:" + i);
      UUID pointUuid = UUID.nameUUIDFromBytes(
          (docId + ":" + expectedRevision + ":" + i).getBytes(StandardCharsets.UTF_8));
      String pointId = pointUuid.toString();
      storedChunks.add(KnowledgeChunkEntity.of(
          pointUuid, userId, kbUuid, docUuid, expectedRevision, i,
          "chunk:" + i, filename, chunks.get(i)));
      storedChunks.getLast().setOrganizationId(kb.getOrganizationId());
      docs.add(org.springframework.ai.document.Document.builder()
          .id(pointId).text(chunks.get(i)).metadata(metadata).build());
    }

    // Persist the lexical mirror first (idempotent replace of this revision), then Qdrant.
    // Both stores key on deterministic ids, so a failed Qdrant write can safely retry.
    chunkRepository.replaceRevision(docUuid, expectedRevision, storedChunks);
    vectorStore.add(docs);
    log.info("Indexed {} chunks for document {} (user={}, kb={})",
        chunks.size(), docId, userId, kbId);
  }

  private KnowledgeDocumentEntity current(UUID documentUuid, int expectedRevision) {
    KnowledgeDocumentEntity current = documentRepository
        .findByDocumentIdWithKnowledgeBase(documentUuid).orElse(null);
    if (current == null || current.getIndexRevision() != expectedRevision
        || current.getStatus() != KnowledgeDocumentStatus.PROCESSING) return null;
    return current;
  }

  private String embeddingSnapshot() {
    return "{\"model\":\"" + embeddingModel + "\",\"dimensions\":1024}";
  }
}
