package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aggregate {@link KnowledgeRetriever} that fans out to every available {@link RetrievalSource}
 * and fuses their candidates.
 *
 * <p>With a single source (today: dense vector) it delegates verbatim, preserving the previous
 * behaviour exactly. With several sources (future lexical/MCP/web) candidates are merged,
 * de-duplicated and passed through the shared {@link KnowledgeRanker}. The merge-by-score here is
 * a deliberate placeholder: when a lexical source is added this point should switch to reciprocal
 * rank fusion, which ranks by per-source position instead of comparing scores across sources with
 * incomparable scales. One failing source never aborts the others.
 */
@Component
@ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
public class HybridKnowledgeRetriever implements KnowledgeRetriever {
  private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);

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
      }
    }
    Duration latency = Duration.ofNanos(System.nanoTime() - started);

    if (outcomes.isEmpty()) {
      return RetrievedKnowledge.unavailable(
          intent.query(), "", "ALL_RETRIEVAL_SOURCES_FAILED", latency);
    }

    LinkedHashMap<String, KnowledgeChunk> unique = new LinkedHashMap<>();
    for (RetrievedKnowledge outcome : outcomes) {
      for (KnowledgeChunk chunk : outcome.chunks()) {
        unique.putIfAbsent(chunk.pointId(), chunk);
      }
    }
    List<KnowledgeChunk> ranked = ranker.rank(
        new ArrayList<>(unique.values()), intent.topK(),
        intent.similarityThreshold(), intent.contextCharacterBudget());

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
