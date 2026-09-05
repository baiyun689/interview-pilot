package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeChunk;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

class DefaultQuestionRagRetrieverTest {

  private final KnowledgeRetriever backend = mock(KnowledgeRetriever.class);
  private final KnowledgeProperties properties =
      KnowledgeProperties.testDefaults(5, 15, 0.5, 6_000);
  private final DefaultQuestionRagRetriever retriever =
      new DefaultQuestionRagRetriever(backend, properties);

  private final QuestionRetrievalSeed seed = new QuestionRetrievalSeed(
      InterviewPhase.FUNDAMENTALS,
      "mysql.mvcc",
      List.of("MVCC", "ReadView"),
      "请解释 MySQL MVCC 的实现原理",
      Difficulty.MEDIUM);
  private final ValidatedKnowledgeScope scope = new ValidatedKnowledgeScope(
      UUID.randomUUID(),
      List.of(UUID.randomUUID()),
      List.of(new ValidatedKnowledgeScope.DocumentRevision(UUID.randomUUID(), 1)),
      "text-embedding-v3");

  @Test
  void returnsNotRequestedWithoutCallingBackendWhenScopeMissing() {
    var snapshot = retriever.retrieve(null, seed);

    assertThat(snapshot.status()).isEqualTo(RagStatus.NOT_REQUESTED);
    assertThat(snapshot.chunks()).isEmpty();
    verifyNoInteractions(backend);
  }

  @Test
  void mapsRetrievedChunksAndCapsAtFourPerQuestion() {
    var chunks = List.of(
        chunk("p1"), chunk("p2"), chunk("p3"), chunk("p4"), chunk("p5"));
    when(backend.retrieve(any(), any())).thenReturn(new RetrievedKnowledge(
        RetrievalStatus.RETRIEVED, seed.query(), "text-embedding-v3",
        chunks, Duration.ZERO, null));

    var snapshot = retriever.retrieve(scope, seed);

    assertThat(snapshot.status()).isEqualTo(RagStatus.RETRIEVED);
    assertThat(snapshot.chunks()).hasSize(4);
    assertThat(snapshot.query()).contains("mysql.mvcc");
  }

  @Test
  void mapsNoMatchToAnEmptySnapshot() {
    when(backend.retrieve(any(), any()))
        .thenReturn(RetrievedKnowledge.noMatch(seed.query(), "text-embedding-v3", Duration.ZERO));

    var snapshot = retriever.retrieve(scope, seed);

    assertThat(snapshot.status()).isEqualTo(RagStatus.NO_MATCH);
    assertThat(snapshot.chunks()).isEmpty();
  }

  @Test
  void degradesToUnavailableWhenBackendThrows() {
    when(backend.retrieve(any(), any())).thenThrow(new RuntimeException("qdrant down"));

    var snapshot = retriever.retrieve(scope, seed);

    assertThat(snapshot.status()).isEqualTo(RagStatus.UNAVAILABLE);
    assertThat(snapshot.failureCode()).isEqualTo("RAG_UNAVAILABLE");
  }

  private KnowledgeChunk chunk(String pointId) {
    return new KnowledgeChunk(
        pointId, UUID.randomUUID(), "reference.md", 0, 0.9, "技术参考内容片段");
  }
}
