package interview.pilot.knowledge.retrieval;

import java.time.Duration;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import interview.pilot.knowledge.infrastructure.KnowledgeChunkRepository;

/** MySQL ngram FULLTEXT candidates, ordered within this source for subsequent RRF fusion. */
@Component
@ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.knowledge", name = "retrieval", havingValue = "hybrid")
public class KeywordRetrievalSource implements RetrievalSource {
  private static final Logger log = LoggerFactory.getLogger(KeywordRetrievalSource.class);
  private final KnowledgeChunkRepository chunks;

  public KeywordRetrievalSource(KnowledgeChunkRepository chunks) {
    this.chunks = chunks;
  }

  @Override
  public String name() {
    return "keyword";
  }

  @Override
  public RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent) {
    long started = System.nanoTime();
    try {
      String query = String.join(" ", intent.keywords().stream()
          .map(String::trim).filter(keyword -> !keyword.isBlank()).distinct().toList());
      if (query.isBlank()) query = intent.query();
      var matches = chunks.search(scope, query, intent.candidateCount());
      var candidates = new ArrayList<KnowledgeChunk>(matches.size());
      for (var match : matches) {
        // A within-source rank score, not a cosine similarity or a calibrated confidence.
        double score = 1.0 / (candidates.size() + 1);
        candidates.add(new KnowledgeChunk(
            match.getPointId().toString(), match.getDocumentId(), match.getFilename(),
            match.getIndexRevision(), match.getChunkIndex(), match.getSection(),
            score, match.getContent(), null));
      }
      Duration latency = Duration.ofNanos(System.nanoTime() - started);
      return candidates.isEmpty()
          ? RetrievedKnowledge.noMatch(intent.query(), "", latency)
          : new RetrievedKnowledge(
              RetrievalStatus.RETRIEVED, intent.query(), "", candidates, latency, null);
    } catch (RuntimeException exception) {
      log.warn("Keyword retrieval source failed for user {}", scope.userId(), exception);
      return RetrievedKnowledge.unavailable(
          intent.query(), "", "KEYWORD_RETRIEVAL_FAILED",
          Duration.ofNanos(System.nanoTime() - started));
    }
  }
}
