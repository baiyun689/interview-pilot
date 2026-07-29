package interview.pilot.knowledge.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;
import interview.pilot.async.infrastructure.AsyncTaskRepository;

class KnowledgeDocumentUploadServiceTest {
  @Test
  void disabledKnowledgeRejectsUploadBeforePersistingFileOrTask() {
    KnowledgeDocumentUploadService service = new KnowledgeDocumentUploadService(
        mock(KnowledgeBaseRepository.class), mock(KnowledgeDocumentRepository.class),
        mock(AsyncTaskRepository.class), mock(KnowledgeDocumentStore.class),
        mock(PlatformTransactionManager.class), properties(false));

    CurrentUser user = new CurrentUser(
        42L, java.util.UUID.randomUUID(), "user@example.com", "User");

    assertThatThrownBy(() -> service.upload(user, java.util.UUID.randomUUID(), null))
        .isInstanceOf(interview.pilot.common.exception.BusinessException.class)
        .satisfies(error -> {
          var business = (interview.pilot.common.exception.BusinessException) error;
          org.assertj.core.api.Assertions.assertThat(business.code())
              .isEqualTo("KNOWLEDGE_DISABLED");
          org.assertj.core.api.Assertions.assertThat(business.status())
              .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
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
