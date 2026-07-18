package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

class KnowledgeRepositoryTest {
  @Test
  void repositoryContractsAlwaysRequireTheKnowledgeBaseOwner() throws Exception {
    Method baseLookup = KnowledgeBaseRepository.class.getMethod(
        "findByKnowledgeBaseIdAndUserAccountId", UUID.class, Long.class);
    KnowledgeDocumentRepository.class.getMethod(
        "findReadyByKnowledgeBaseIdsAndUserAccountId", Collection.class, Long.class);
    Method readyDocuments = KnowledgeDocumentJpaRepository.class.getMethod(
        "findReadyByKnowledgeBaseIdsAndUserAccountId", Collection.class, Long.class);

    assertThat(baseLookup.getReturnType().getSimpleName()).isEqualTo("Optional");
    assertThat(readyDocuments.getAnnotation(Query.class).value())
        .contains("knowledgeBase.userAccountId = :userAccountId")
        .contains("document.status = interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY");
  }

  @Test
  void publicPortsDoNotExposeUnscopedJpaCrudMethods() {
    assertThat(JpaRepository.class.isAssignableFrom(KnowledgeBaseRepository.class)).isFalse();
    assertThat(JpaRepository.class.isAssignableFrom(KnowledgeDocumentRepository.class)).isFalse();
    assertThat(methodNames(KnowledgeBaseRepository.class))
        .doesNotContain("findById", "findAll", "delete", "deleteById", "deleteAll");
    assertThat(methodNames(KnowledgeDocumentRepository.class))
        .doesNotContain("findById", "findAll", "delete", "deleteById", "deleteAll");
  }

  @Test
  void baseAdapterDelegatesOnlyTheOwnerScopedLookup() {
    var delegate = mock(KnowledgeBaseJpaRepository.class);
    var adapter = new KnowledgeBaseRepositoryAdapter(delegate);
    UUID knowledgeBaseId = UUID.randomUUID();

    when(delegate.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, 42L))
        .thenReturn(Optional.empty());

    assertThat(adapter.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, 42L)).isEmpty();

    verify(delegate).findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, 42L);
  }

  @Test
  void documentAdapterDelegatesTheOwnerScopedReadyQuery() {
    var delegate = mock(KnowledgeDocumentJpaRepository.class);
    var adapter = new KnowledgeDocumentRepositoryAdapter(delegate);
    Collection<UUID> knowledgeBaseIds = List.of(UUID.randomUUID());

    when(delegate.findReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, 42L))
        .thenReturn(List.of());

    assertThat(adapter.findReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, 42L)).isEmpty();

    verify(delegate).findReadyByKnowledgeBaseIdsAndUserAccountId(knowledgeBaseIds, 42L);
  }

  private List<String> methodNames(Class<?> type) {
    return Stream.of(type.getMethods()).map(Method::getName).toList();
  }
}
