package interview.pilot.knowledge.indexing;

import java.util.List;
import java.util.UUID;

/** Narrow read model used only by the deferred vector revision cleanup job. */
public interface KnowledgeRevisionCandidates {
  List<Candidate> findEligible();

  record Candidate(UUID documentId, int activeRevision) {}
}
