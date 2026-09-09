package interview.pilot.knowledge.infrastructure;

import java.util.List;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

/** Spring Data fragment for a native FULLTEXT query with a dynamic document/revision scope. */
public interface KnowledgeChunkSearchRepository {
  List<KnowledgeChunkEntity> search(
      ValidatedKnowledgeScope scope, String query, int candidateCount);
}
