package interview.pilot.interview.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeChunk;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.knowledge.retrieval.RetrievalIntent;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

@Component
public class DefaultQuestionRagRetriever implements QuestionRagRetriever {

  private static final Logger log = LoggerFactory.getLogger(DefaultQuestionRagRetriever.class);
  private static final int MAX_CHUNKS_PER_QUESTION = 4;

  private final KnowledgeRetriever knowledgeRetriever;
  private final KnowledgeProperties properties;

  public DefaultQuestionRagRetriever(
      KnowledgeRetriever knowledgeRetriever, KnowledgeProperties properties) {
    this.knowledgeRetriever = knowledgeRetriever;
    this.properties = properties;
  }

  @Override
  public RagContextSnapshot retrieve(ValidatedKnowledgeScope scope, QuestionRetrievalSeed seed) {
    if (scope == null) {
      return new RagContextSnapshot(RagStatus.NOT_REQUESTED, "", "", List.of(), null);
    }

    String query = seed.query();
    int topK = Math.min(MAX_CHUNKS_PER_QUESTION, properties.topK());
    RetrievalIntent intent = new RetrievalIntent(
        query,
        seed.phase().name(),
        seed.difficulty().name(),
        seed.keywords(),
        List.of(),
        topK,
        properties.candidateCount(),
        properties.similarityThreshold(),
        properties.contextCharacterBudget());

    try {
      RetrievedKnowledge result = knowledgeRetriever.retrieve(scope, intent);
      return mapResult(result);
    } catch (RuntimeException exception) {
      log.warn(
          "Question RAG retrieval failed for phase={} point={} code={}",
          seed.phase(), seed.knowledgePoint(), exception.getMessage());
      return new RagContextSnapshot(
          RagStatus.UNAVAILABLE, query, scope.embeddingVersion(), List.of(), "RAG_UNAVAILABLE");
    }
  }

  private RagContextSnapshot mapResult(RetrievedKnowledge result) {
    RagStatus status = switch (result.status()) {
      case RetrievalStatus.RETRIEVED -> RagStatus.RETRIEVED;
      case RetrievalStatus.NO_MATCH -> RagStatus.NO_MATCH;
      case RetrievalStatus.UNAVAILABLE -> RagStatus.UNAVAILABLE;
    };
    List<RagContextSnapshot.Chunk> chunks = result.chunks().stream()
        .limit(MAX_CHUNKS_PER_QUESTION)
        .map(DefaultQuestionRagRetriever::toSnapshotChunk)
        .toList();
    return new RagContextSnapshot(
        status, result.query(), result.embeddingModel(), chunks, result.failureReason());
  }

  private static RagContextSnapshot.Chunk toSnapshotChunk(KnowledgeChunk chunk) {
    return new RagContextSnapshot.Chunk(
        chunk.pointId(),
        chunk.documentId(),
        chunk.filename(),
        chunk.documentRevision(),
        chunk.chunkIndex(),
        KnowledgeRole.TECHNICAL_REFERENCE,
        chunk.section(),
        chunk.pageNumber(),
        chunk.score(),
        chunk.content());
  }
}
