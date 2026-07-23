package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;

class KnowledgeScopeResolverTest {
  private KnowledgeBaseRepository baseRepository;
  private KnowledgeDocumentRepository documentRepository;
  private KnowledgeScopeResolver resolver;
  private final CurrentUser userA = new CurrentUser(1L, UUID.randomUUID(), "a@test", "A");
  private final CurrentUser userB = new CurrentUser(2L, UUID.randomUUID(), "b@test", "B");
  private final UUID ownedBase = UUID.randomUUID();
  private final UUID foreignBase = UUID.randomUUID();
  private final UUID readyDoc = UUID.randomUUID();
  private final UUID emptyDoc = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    baseRepository = mock(KnowledgeBaseRepository.class);
    documentRepository = mock(KnowledgeDocumentRepository.class);
    resolver = new KnowledgeScopeResolver(baseRepository, documentRepository,
        "text-embedding-v3");

    var owned = KnowledgeBaseEntity.active(userA.databaseId(), "Owned KB");
    when(baseRepository.findByKnowledgeBaseIdAndUserAccountId(ownedBase, userA.databaseId()))
        .thenReturn(Optional.of(owned));
    when(baseRepository.findByKnowledgeBaseIdAndUserAccountId(foreignBase, userA.databaseId()))
        .thenReturn(Optional.empty());

    var ready = mock(KnowledgeDocumentEntity.class);
    when(ready.getDocumentId()).thenReturn(readyDoc);
    when(ready.getIndexRevision()).thenReturn(1);
    when(ready.getStatus()).thenReturn(KnowledgeDocumentStatus.READY);

    when(documentRepository.findReadyByKnowledgeBaseIdsAndUserAccountId(
        List.of(ownedBase), userA.databaseId()))
        .thenReturn(List.of(ready));
  }

  @Test
  void resolvesScopeWithReadyDocuments() {
    var scope = resolver.resolveForCreation(userA, List.of(ownedBase));

    assertThat(scope.userId()).isEqualTo(userA.userId());
    assertThat(scope.knowledgeBaseIds()).containsExactly(ownedBase);
    assertThat(scope.documents()).hasSize(1);
    assertThat(scope.documents().getFirst().documentId()).isEqualTo(readyDoc);
    assertThat(scope.documents().getFirst().indexRevision()).isEqualTo(1);
    assertThat(scope.embeddingVersion()).isEqualTo("text-embedding-v3");
  }

  @Test
  void rejectsWhenAnyRequestedBaseBelongsToAnotherUser() {
    assertThatThrownBy(() -> resolver.resolveForCreation(userA, List.of(ownedBase, foreignBase)))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo("KNOWLEDGE_BASE_NOT_FOUND");
  }

  @Test
  void rejectsWhenNoDocumentsAreReady() {
    when(documentRepository.findReadyByKnowledgeBaseIdsAndUserAccountId(
        List.of(ownedBase), userA.databaseId()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> resolver.resolveForCreation(userA, List.of(ownedBase)))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo("NO_READY_DOCUMENTS");
  }

  @Test
  void rejectsEmptyKnowledgeBaseList() {
    assertThatThrownBy(() -> resolver.resolveForCreation(userA, List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo("KNOWLEDGE_BASE_REQUIRED");
  }
}
