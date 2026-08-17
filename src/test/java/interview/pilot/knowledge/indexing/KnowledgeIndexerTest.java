package interview.pilot.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;

class KnowledgeIndexerTest {
  @Test
  void embedsAndUpsertsChunksWithTenantDocumentAndRevisionMetadata() throws Exception {
    KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    KnowledgeDocumentStore store = mock(KnowledgeDocumentStore.class);
    KnowledgeDocumentParser parser = mock(KnowledgeDocumentParser.class);
    RecursiveTextSplitter splitter = mock(RecursiveTextSplitter.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    VectorStore vectorStore = mock(VectorStore.class);

    Long accountId = 42L;
    UserAccountEntity account = UserAccountEntity.register(
        "rag-indexer@example.com", "hash", "RAG Indexer");
    KnowledgeBaseEntity base = KnowledgeBaseEntity.active(accountId, "Backend notes");
    UUID knowledgeBaseId = UUID.randomUUID();
    ReflectionTestUtils.setField(base, "knowledgeBaseId", knowledgeBaseId);
    KnowledgeDocumentEntity document = KnowledgeDocumentEntity.pending(
        base, "notes.md", "hash", account.getUserId() + "/" + UUID.randomUUID() + "/source");
    document.beginReindex();
    UUID documentId = UUID.randomUUID();
    ReflectionTestUtils.setField(document, "documentId", documentId);

    when(documents.findByDocumentIdWithKnowledgeBase(documentId)).thenReturn(Optional.of(document));
    when(store.open(document.getStorageKey())).thenReturn(
        new ByteArrayInputStream("Spring transactions".getBytes(StandardCharsets.UTF_8)));
    when(parser.parse(any(), eq("notes.md"), eq(10L * 1024 * 1024L)))
        .thenReturn("Spring transactions");
    when(splitter.split("Spring transactions")).thenReturn(
        List.of("Spring transactions", "Propagation and isolation"));
    when(users.findById(accountId)).thenReturn(Optional.of(account));

    KnowledgeIndexer indexer = new KnowledgeIndexer(
        documents, store, parser, splitter, users, Optional.of(vectorStore),
        "text-embedding-v3");

    int chunks = indexer.index(documentId, 1);

    org.mockito.ArgumentCaptor<List<Document>> captured =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(vectorStore).add(captured.capture());
    assertThat(chunks).isEqualTo(2);
    assertThat(captured.getValue()).hasSize(2);
    assertThat(captured.getValue()).allSatisfy(chunk -> {
      assertThat(chunk.getMetadata()).containsEntry("user_id", account.getUserId().toString())
          .containsEntry("knowledge_base_id", knowledgeBaseId.toString())
          .containsEntry("document_id", documentId.toString())
          .containsEntry("index_revision", "1")
          .containsKey("section")
          .containsKey("chunk_index");
      assertThat(chunk.getId()).isEqualTo(UUID.nameUUIDFromBytes(
          (documentId + ":1:" + chunk.getMetadata().get("chunk_index"))
              .getBytes(StandardCharsets.UTF_8)).toString());
    });
    verify(vectorStore, never()).delete(any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
  }

  @Test
  void refusesToCompleteIndexingWhenVectorStoreIsUnavailable() {
    KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    KnowledgeDocumentStore store = mock(KnowledgeDocumentStore.class);
    KnowledgeDocumentParser parser = mock(KnowledgeDocumentParser.class);
    RecursiveTextSplitter splitter = mock(RecursiveTextSplitter.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    UUID documentId = UUID.randomUUID();
    when(documents.findByDocumentIdWithKnowledgeBase(documentId)).thenReturn(Optional.of(
        KnowledgeDocumentEntity.pending(
            KnowledgeBaseEntity.active(42L, "Backend notes"), "notes.md", "hash",
            UUID.randomUUID() + "/" + documentId + "/source")));

    KnowledgeDocumentEntity document = documents.findByDocumentIdWithKnowledgeBase(documentId)
        .orElseThrow();
    document.beginReindex();
    KnowledgeIndexer indexer = new KnowledgeIndexer(
        documents, store, parser, splitter, users, Optional.empty(), "text-embedding-v3");

    assertThatThrownBy(() -> indexer.index(documentId, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Knowledge vector store is unavailable");
  }
}
