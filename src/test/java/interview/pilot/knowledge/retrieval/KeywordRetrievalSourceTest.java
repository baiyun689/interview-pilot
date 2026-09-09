package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.knowledge.infrastructure.KnowledgeChunkEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeChunkRepository;

class KeywordRetrievalSourceTest {
  private final KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
  private final KeywordRetrievalSource source = new KeywordRetrievalSource(repository);
  private final UUID user = UUID.randomUUID();
  private final UUID base = UUID.randomUUID();
  private final UUID document = UUID.randomUUID();
  private final ValidatedKnowledgeScope scope = new ValidatedKnowledgeScope(
      user, List.of(base), List.of(new ValidatedKnowledgeScope.DocumentRevision(document, 2)), "v3");

  @Test
  void passesWholeScopeAndKeywordsAndPreservesPointIdentityAndCandidatePool() {
    var first = entity("事务传播与嵌套事务", 0);
    var second = entity("REQUIRES_NEW 的挂起与恢复", 1);
    when(repository.search(scope, "事务传播 REQUIRES_NEW", 12)).thenReturn(List.of(first, second));

    var result = source.retrieve(scope, intent(List.of(" 事务传播 ", "", "REQUIRES_NEW", "事务传播")));

    assertThat(source.name()).isEqualTo("keyword");
    verify(repository).search(scope, "事务传播 REQUIRES_NEW", 12);
    assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
    // topK=1 and threshold=.99 are final/vector constraints, not lexical candidate filters.
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst()).isEqualTo(new KnowledgeChunk(
        first.getPointId().toString(), document, "notes.md", 2, 0, "chunk:0",
        1.0, first.getContent(), null));
    assertThat(result.chunks().get(1).score()).isEqualTo(0.5);
    assertThat(result.query()).isEqualTo("请解释事务传播");
    assertThat(result.embeddingModel()).isEmpty();
  }

  @Test
  void blankKeywordsFallBackToQuestionAndNoResultsReturnNoMatch() {
    when(repository.search(scope, "请解释事务传播", 12)).thenReturn(List.of());
    assertThat(source.retrieve(scope, intent(List.of(" "))).status())
        .isEqualTo(RetrievalStatus.NO_MATCH);
    verify(repository).search(scope, "请解释事务传播", 12);
  }

  @Test
  void databaseFailureIsUnavailableWithSafeReason() {
    when(repository.search(scope, "请解释事务传播", 12))
        .thenThrow(new IllegalStateException("private SQL details"));
    var result = source.retrieve(scope, intent(List.of()));
    assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
    assertThat(result.failureReason()).isEqualTo("KEYWORD_RETRIEVAL_FAILED");
    assertThat(result.chunks()).isEmpty();
  }

  private RetrievalIntent intent(List<String> keywords) {
    return new RetrievalIntent("请解释事务传播", "backend", "MEDIUM", keywords, List.of(),
        1, 12, 0.99, 100);
  }

  private KnowledgeChunkEntity entity(String content, int index) {
    return KnowledgeChunkEntity.of(UUID.randomUUID(), user, base, document, 2, index,
        "chunk:" + index, "notes.md", content);
  }
}
