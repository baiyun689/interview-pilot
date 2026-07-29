package interview.pilot.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;
import interview.pilot.async.infrastructure.AsyncTaskRepository;

class KnowledgeDocumentUploadServiceTest {
  @Test
  void disabledKnowledgeRejectsUploadBeforePersistingFileOrTask() {
    KnowledgeDocumentUploadService service = new KnowledgeDocumentUploadService(
        mock(KnowledgeBaseRepository.class), mock(KnowledgeDocumentRepository.class),
        mock(AsyncTaskRepository.class), mock(KnowledgeDocumentStore.class),
        mock(PlatformTransactionManager.class), properties(false), Optional.empty());

    CurrentUser user = new CurrentUser(
        42L, java.util.UUID.randomUUID(), "user@example.com", "User");

    assertThatThrownBy(() -> service.upload(user, java.util.UUID.randomUUID(), null))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          var business = (BusinessException) error;
          org.assertj.core.api.Assertions.assertThat(business.code())
              .isEqualTo("KNOWLEDGE_DISABLED");
          org.assertj.core.api.Assertions.assertThat(business.status())
              .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
  }

  @Test
  void listDocumentsReturnsEveryVisibleStatus() {
    var baseId = UUID.randomUUID();
    var user = new CurrentUser(42L, UUID.randomUUID(), "user@example.com", "User");
    var base = base(baseId, user.databaseId());
    var processing = document(base, "processing.md", KnowledgeDocumentStatus.PROCESSING);
    var failed = document(base, "failed.md", KnowledgeDocumentStatus.FAILED);
    var bases = mock(KnowledgeBaseRepository.class);
    var documents = mock(KnowledgeDocumentRepository.class);
    KnowledgeDocumentUploadService service = new KnowledgeDocumentUploadService(
        bases, documents, mock(AsyncTaskRepository.class), mock(KnowledgeDocumentStore.class),
        new TestTransactionManager(), properties(true), Optional.empty());
    when(bases.findByKnowledgeBaseIdAndUserAccountId(baseId, user.databaseId()))
        .thenReturn(Optional.of(base));
    when(documents.findVisibleByKnowledgeBaseIdsAndUserAccountId(
        List.of(baseId), user.databaseId())).thenReturn(List.of(processing, failed));

    var result = service.listDocuments(user, baseId);

    assertThat(result).extracting("status").containsExactly("PROCESSING", "FAILED");
  }

  @Test
  void deleteMarksDocumentDeletedAndCleansStoredFileAndVectors() {
    var baseId = UUID.randomUUID();
    var docId = UUID.randomUUID();
    var user = new CurrentUser(42L, UUID.randomUUID(), "user@example.com", "User");
    var base = base(baseId, user.databaseId());
    var document = document(base, "notes.md", KnowledgeDocumentStatus.READY);
    ReflectionTestUtils.setField(document, "documentId", docId);
    var bases = mock(KnowledgeBaseRepository.class);
    var documents = mock(KnowledgeDocumentRepository.class);
    var store = mock(KnowledgeDocumentStore.class);
    var vectorStore = mock(VectorStore.class);
    KnowledgeDocumentUploadService service = new KnowledgeDocumentUploadService(
        bases, documents, mock(AsyncTaskRepository.class), store,
        new TestTransactionManager(), properties(true), Optional.of(vectorStore));
    when(bases.findByKnowledgeBaseIdAndUserAccountId(baseId, user.databaseId()))
        .thenReturn(Optional.of(base));
    when(documents.findByDocumentIdWithKnowledgeBase(docId)).thenReturn(Optional.of(document));
    when(documents.findByDocumentId(docId)).thenReturn(Optional.of(document));
    when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.delete(user, baseId, docId);

    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.DELETED);
    verify(vectorStore).delete(any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
    verify(store).delete("storage/notes.md");
  }

  private static KnowledgeBaseEntity base(UUID baseId, Long userAccountId) {
    var base = KnowledgeBaseEntity.active(userAccountId, "Backend notes");
    ReflectionTestUtils.setField(base, "knowledgeBaseId", baseId);
    return base;
  }

  private static KnowledgeDocumentEntity document(
      KnowledgeBaseEntity base, String filename, KnowledgeDocumentStatus status) {
    var document = KnowledgeDocumentEntity.pending(base, filename, "hash", "storage/" + filename);
    if (status == KnowledgeDocumentStatus.PROCESSING) {
      document.beginReindex();
    } else if (status == KnowledgeDocumentStatus.READY) {
      int revision = document.beginReindex();
      document.markReady(revision, "parsed", 1);
    } else if (status == KnowledgeDocumentStatus.FAILED) {
      int revision = document.beginReindex();
      document.markFailed(revision, "parse failed");
    }
    return document;
  }

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() throws CannotCreateTransactionException {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  private static KnowledgeProperties properties(boolean enabled) {
    return new KnowledgeProperties(
        enabled, Path.of("build/tmp/knowledge-service-test"), 800, 100, 32,
        "knowledge_chunks_v1", 5, 0.72,
        new KnowledgeProperties.Qdrant("localhost", 6334),
        new KnowledgeProperties.Embedding(
            URI.create("https://dashscope.aliyuncs.com/compatible-mode"),
            "test-key", "text-embedding-v3", 1024));
  }
}
