package interview.pilot.interview.application;

import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

/**
 * Question-scoped RAG retrieval. One call retrieves the reference chunks for a single question
 * (driven by its knowledge point), replacing the old phase-wide retrieval shared by every question
 * in a phase. Implementations never throw: a missing scope, no match or backend failure is mapped
 * to the corresponding {@link RagContextSnapshot} status so question preparation always proceeds.
 */
public interface QuestionRagRetriever {

  RagContextSnapshot retrieve(ValidatedKnowledgeScope scope, QuestionRetrievalSeed seed);
}
