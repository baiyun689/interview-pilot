package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class KnowledgeRepositoryTest {
  @Test
  void repositoryContractsAlwaysRequireTheKnowledgeBaseOwner() throws Exception {
    Method baseLookup = KnowledgeBaseRepository.class.getMethod(
        "findByKnowledgeBaseIdAndUserAccountId", UUID.class, Long.class);
    Method readyDocuments = KnowledgeDocumentRepository.class.getMethod(
        "findReadyByKnowledgeBaseIdsAndUserAccountId", Collection.class, Long.class);

    assertThat(baseLookup.getReturnType().getSimpleName()).isEqualTo("Optional");
    assertThat(readyDocuments.getAnnotation(Query.class).value())
        .contains("knowledgeBase.userAccountId = :userAccountId")
        .contains("document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY");
  }
}
