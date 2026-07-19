package interview.pilot.knowledge.retrieval;

public interface KnowledgeRetriever {
  RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent);
}
