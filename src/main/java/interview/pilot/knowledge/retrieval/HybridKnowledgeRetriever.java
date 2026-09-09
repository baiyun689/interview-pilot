package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aggregate {@link KnowledgeRetriever} that fans out to every available {@link RetrievalSource}
 * and fuses their candidates using reciprocal rank fusion (RRF, k=60).
 *
 * <p>With a single source (today: dense vector) it delegates verbatim, preserving the previous
 * behaviour exactly. With several sources, only per-source positions contribute to ranking;
 * raw FULLTEXT and vector scores are never compared. Final deduplication, top-K and character
 * budgets are applied after fusion. One failing source never aborts the others.
 */
@Component
@ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
public class HybridKnowledgeRetriever implements KnowledgeRetriever {
  private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);
  private static final int RRF_K = 60;

  private final List<RetrievalSource> sources;
  private final KnowledgeRanker ranker;

  public HybridKnowledgeRetriever(List<RetrievalSource> sources, KnowledgeRanker ranker) {
    this.sources = sources.stream()
        .sorted(Comparator.comparing(RetrievalSource::name))
        .toList();
    this.ranker = ranker;
  }

  @Override
  public RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent) {
    if (sources.isEmpty()) {
      return RetrievedKnowledge.unavailable(
          intent.query(), "", "NO_RETRIEVAL_SOURCE", Duration.ZERO);
    }
    if (sources.size() == 1) {
      return sources.getFirst().retrieve(scope, intent);
    }
    return fuse(scope, intent);
  }

  private RetrievedKnowledge fuse(ValidatedKnowledgeScope scope, RetrievalIntent intent) {
    long started = System.nanoTime();
    List<RetrievedKnowledge> outcomes = new ArrayList<>();
    for (RetrievalSource source : sources) {
      try {
        outcomes.add(source.retrieve(scope, intent));
      } catch (RuntimeException exception) {
        // A single broken source must not take down the remaining sources.
        log.warn("Retrieval source {} failed and was skipped", source.name(), exception);
        outcomes.add(RetrievedKnowledge.unavailable(
            intent.query(), "", "RETRIEVAL_SOURCE_FAILED", Duration.ZERO));
      }
    }
    Duration latency = Duration.ofNanos(System.nanoTime() - started);

    LinkedHashMap<String, KnowledgeChunk> unique = new LinkedHashMap<>();
    LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
    for (RetrievedKnowledge outcome : outcomes) {
      var seen = new HashSet<String>();
      int rank = 0;
      for (KnowledgeChunk chunk : outcome.chunks()) {
        // A source contributes at most once per point, even if it returns duplicate rows.
        if (!seen.add(chunk.pointId())) continue;
        rank++;
        unique.putIfAbsent(chunk.pointId(), chunk);
        scores.merge(chunk.pointId(), 1.0 / (RRF_K + rank), Double::sum);
      }
    }
    // Divide by the theoretical maximum (all sources rank a point first). This keeps the
    // KnowledgeChunk score in [0,1] without changing RRF ordering, including source failures.
    double scale = (RRF_K + 1.0) / sources.size();
    List<KnowledgeChunk> fused = unique.values().stream().map(chunk -> new KnowledgeChunk(
        chunk.pointId(), chunk.documentId(), chunk.filename(), chunk.documentRevision(),
        chunk.chunkIndex(), chunk.section(), Math.min(1.0, scores.get(chunk.pointId()) * scale),
        chunk.content(), chunk.pageNumber())).toList();
    List<KnowledgeChunk> ranked = ranker.rank(
        fused, intent.topK(), 0.0, intent.contextCharacterBudget());

    String embeddingModel = outcomes.stream()
        .map(RetrievedKnowledge::embeddingModel)
        .filter(model -> model != null && !model.isBlank())
        .findFirst().orElse("");

    if (!ranked.isEmpty()) {
      return new RetrievedKnowledge(
          RetrievalStatus.RETRIEVED, intent.query(), embeddingModel, ranked, latency, null);
    }
    boolean anyUnavailable = outcomes.stream()
        .anyMatch(outcome -> outcome.status() == RetrievalStatus.UNAVAILABLE);
    if (anyUnavailable) {
      String reason = outcomes.stream()
          .filter(outcome -> outcome.status() == RetrievalStatus.UNAVAILABLE)
          .map(RetrievedKnowledge::failureReason)
          .filter(r -> r != null && !r.isBlank())
          .findFirst().orElse("RETRIEVAL_SOURCE_UNAVAILABLE");
      return RetrievedKnowledge.unavailable(intent.query(), embeddingModel, reason, latency);
    }
    return RetrievedKnowledge.noMatch(intent.query(), embeddingModel, latency);
  }
}
