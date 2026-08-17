package interview.pilot.interview.grounding;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

public interface KnowledgeGrounding {
  GroundingSnapshot ground(ValidatedKnowledgeScope scope, GroundingDirective directive);
}
