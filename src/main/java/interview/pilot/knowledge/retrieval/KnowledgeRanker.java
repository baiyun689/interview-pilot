package interview.pilot.knowledge.retrieval;

import java.util.List;

/**
 * Ranks and trims retrieval candidates into the final context window.
 *
 * <p>Abstracted behind an interface so a single-source vector retriever and a multi-source
 * hybrid retriever can share (or independently replace) the ranking strategy, e.g. swapping the
 * default score ordering for reciprocal rank fusion without touching the callers.
 */
public interface KnowledgeRanker {
  List<KnowledgeChunk> rank(
      List<KnowledgeChunk> candidates, int topK, double minimumScore, int characterBudget);
}
