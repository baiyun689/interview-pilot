package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
  private static final int MIN_SEARCH_CANDIDATES = 12;

  private final VectorStore vectorStore;
  private final KnowledgeProperties properties;
  private final AiMetrics metrics;
  private final String embeddingModel;

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
      int candidateCount = Math.max(MIN_SEARCH_CANDIDATES, intent.topK() * 3);
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

      List<KnowledgeChunk> chunks = deduplicate(toChunks(results), intent.topK());
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
      int chunkIndex = parseInt(meta.get("chunk_index"));
      double score = 0.0; // Spring AI VectorStore abstraction does not expose per-document scores

      chunks.add(new KnowledgeChunk(pointId, documentId, filename, chunkIndex, score, doc.getText()));
    }
    return chunks;
  }

  private List<KnowledgeChunk> deduplicate(List<KnowledgeChunk> chunks, int maxCount) {
    if (chunks.size() <= maxCount) return chunks;

    // Keep highest-scoring chunk per document
    List<KnowledgeChunk> result = new ArrayList<>();
    Set<String> seenDocumentIds = new HashSet<>();
    for (KnowledgeChunk chunk : chunks) {
      String docKey = chunk.documentId().toString();
      if (seenDocumentIds.add(docKey)) {
        result.add(chunk);
      }
    }
    if (result.size() <= maxCount) return result;

    // Cap at maxCount
    return List.copyOf(result.subList(0, Math.min(maxCount, result.size())));
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

  private static String describe(RuntimeException exception) {
    String message = exception.getMessage();
    return message != null ? message : "Qdrant search failed";
  }
}
