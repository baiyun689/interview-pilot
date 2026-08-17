package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import interview.pilot.common.observability.AiMetrics;
import interview.pilot.knowledge.config.KnowledgeProperties;

@Component
@ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
public class QdrantKnowledgeRetriever implements KnowledgeRetriever {
  private static final Logger log = LoggerFactory.getLogger(QdrantKnowledgeRetriever.class);
  private final VectorStore vectorStore;
  private final KnowledgeProperties properties;
  private final AiMetrics metrics;
  private final String embeddingModel;
  private final KnowledgeRanker ranker = new KnowledgeRanker();

  public QdrantKnowledgeRetriever(
      VectorStore vectorStore, KnowledgeProperties properties, AiMetrics metrics) {
    this.vectorStore = vectorStore;
    this.properties = properties;
    this.metrics = metrics;
    this.embeddingModel = properties.embedding().model();
  }

  @Override
  public RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent) {
    long started = System.nanoTime();
    try {
      Filter.Expression filter = buildFilter(scope);
      int candidateCount = intent.candidateCount();
      var request = SearchRequest.builder()
          .query(intent.query())
          .topK(candidateCount)
          .similarityThreshold(intent.similarityThreshold())
          .filterExpression(filter)
          .build();

      List<Document> results = vectorStore.similaritySearch(request);
      Duration latency = Duration.ofNanos(System.nanoTime() - started);

      if (results.isEmpty()) {
        metrics.knowledgeRetrievalDuration("NO_MATCH", latency, 0);
        return RetrievedKnowledge.noMatch(intent.query(), embeddingModel, latency);
      }

      List<KnowledgeChunk> chunks = ranker.rank(
          toChunks(results),
          intent.topK(), intent.similarityThreshold(),
          intent.contextCharacterBudget());
      if (chunks.isEmpty()) {
        metrics.knowledgeRetrievalDuration("NO_MATCH", latency, 0);
        return RetrievedKnowledge.noMatch(intent.query(), embeddingModel, latency);
      }
      metrics.knowledgeRetrievalDuration("RETRIEVED", latency, chunks.size());
      return new RetrievedKnowledge(
          RetrievalStatus.RETRIEVED, intent.query(), embeddingModel,
          chunks, latency, null);
    } catch (RuntimeException exception) {
      Duration latency = Duration.ofNanos(System.nanoTime() - started);
      log.warn("Knowledge retrieval failed for user {}", scope.userId(), exception);
      metrics.knowledgeRetrievalDuration("UNAVAILABLE", latency, 0);
      return RetrievedKnowledge.unavailable(
          intent.query(), embeddingModel, describe(exception), latency);
    }
  }

  private Filter.Expression buildFilter(ValidatedKnowledgeScope scope) {
    var userFilter = eq("user_id", scope.userId().toString());
    var kbFilter = in("knowledge_base_id", scope.knowledgeBaseIds().stream()
        .map(UUID::toString).toList());

    var docFilters = scope.documents().stream()
        .map(doc -> and(
            eq("document_id", doc.documentId().toString()),
            eq("index_revision", String.valueOf(doc.indexRevision()))))
        .toList();

    Filter.Expression documentFilter = foldOr(docFilters);
    return and(and(userFilter, kbFilter), documentFilter);
  }

  private static Filter.Expression eq(String key, String value) {
    return new Filter.Expression(Filter.ExpressionType.EQ,
        new Filter.Key(key), new Filter.Value(value));
  }

  private static Filter.Expression in(String key, List<String> values) {
    return new Filter.Expression(Filter.ExpressionType.IN,
        new Filter.Key(key), new Filter.Value(values));
  }

  private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
    return new Filter.Expression(Filter.ExpressionType.AND, left, right);
  }

  private static Filter.Expression foldOr(List<Filter.Expression> expressions) {
    if (expressions.isEmpty()) {
      throw new IllegalArgumentException("At least one document filter is required");
    }
    Filter.Expression result = expressions.getFirst();
    for (int i = 1; i < expressions.size(); i++) {
      result = new Filter.Expression(Filter.ExpressionType.OR, result, expressions.get(i));
    }
    return result;
  }

  private List<KnowledgeChunk> toChunks(List<Document> documents) {
    List<KnowledgeChunk> chunks = new ArrayList<>();
    for (Document doc : documents) {
      Map<String, Object> meta = doc.getMetadata();
      String pointId = doc.getId();
      UUID documentId = parseUuid(meta.get("document_id"));
      if (documentId == null) continue;
      String filename = stringOrEmpty(meta.get("filename"));
      int documentRevision = Math.max(1, parseInt(meta.get("index_revision")));
      int chunkIndex = parseInt(meta.get("chunk_index"));
      String section = stringOrEmpty(meta.get("section"));
      Integer pageNumber = positiveInt(meta.get("page_number"));
      Double documentScore = doc.getScore();
      double score = documentScore == null ? 0.0 : documentScore;

      chunks.add(new KnowledgeChunk(
          pointId, documentId, filename, documentRevision, chunkIndex, section,
          score, doc.getText(), pageNumber));
    }
    return chunks;
  }

  private static UUID parseUuid(Object value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value.toString());
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String stringOrEmpty(Object value) {
    return value == null ? "" : value.toString();
  }

  private static int parseInt(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(value == null ? "0" : value.toString());
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private static Integer positiveInt(Object value) {
    int parsed = parseInt(value);
    return parsed > 0 ? parsed : null;
  }

  private static String describe(RuntimeException exception) {
    String message = exception.getMessage();
    return message != null ? message : "Qdrant search failed";
  }
}
