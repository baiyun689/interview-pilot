package interview.pilot.knowledge.retrieval;

/**
 * A single candidate source for knowledge retrieval (dense vector, lexical/BM25, MCP external
 * knowledge, web search, ...). Each source is independent and returns its own
 * {@link RetrievedKnowledge}; the {@link KnowledgeRetriever} aggregate decides how to fuse them.
 */
public interface RetrievalSource {
  RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent);

  /** Stable identifier used for logging, metrics and future per-source fusion. */
  default String name() {
    return getClass().getSimpleName();
  }
}
