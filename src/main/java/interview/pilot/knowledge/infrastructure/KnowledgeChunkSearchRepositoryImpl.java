package interview.pilot.knowledge.infrastructure;

import java.util.List;
import java.util.StringJoiner;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import jakarta.persistence.EntityManager;

class KnowledgeChunkSearchRepositoryImpl implements KnowledgeChunkSearchRepository {
  private final EntityManager entityManager;

  KnowledgeChunkSearchRepositoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<KnowledgeChunkEntity> search(
      ValidatedKnowledgeScope scope, String query, int candidateCount) {
    if (scope == null || query == null || query.isBlank() || candidateCount < 1) {
      throw new IllegalArgumentException("Validated scope, query and positive candidate count are required");
    }
    // Only placeholder names are generated; every value, including FULLTEXT input, is bound.
    StringJoiner bases = new StringJoiner(", ");
    for (int i = 0; i < scope.knowledgeBaseIds().size(); i++) bases.add(":kb" + i);
    StringJoiner revisions = new StringJoiner(" OR ");
    for (int i = 0; i < scope.documents().size(); i++) {
      revisions.add("(document_id = :doc" + i + " AND index_revision = :rev" + i + ")");
    }
    String sql = "SELECT * FROM knowledge_chunk WHERE "
        + (scope.organizationId() == null ? "user_id = :owner AND organization_id IS NULL" : "organization_id = :owner AND user_id IS NULL")
        + " AND knowledge_base_id IN (" + bases + ")"
        + " AND (" + revisions + ")"
        + " AND MATCH(content) AGAINST(:query IN NATURAL LANGUAGE MODE) > 0"
        + " ORDER BY MATCH(content) AGAINST(:query IN NATURAL LANGUAGE MODE) DESC, point_id ASC";
    var search = entityManager.createNativeQuery(sql, KnowledgeChunkEntity.class)
        .setParameter("owner", scope.organizationId() == null ? scope.userId().toString() : scope.organizationId())
        .setParameter("query", query)
        .setMaxResults(candidateCount);
    for (int i = 0; i < scope.knowledgeBaseIds().size(); i++) {
      search.setParameter("kb" + i, scope.knowledgeBaseIds().get(i).toString());
    }
    for (int i = 0; i < scope.documents().size(); i++) {
      var document = scope.documents().get(i);
      search.setParameter("doc" + i, document.documentId().toString());
      search.setParameter("rev" + i, document.indexRevision());
    }
    return search.getResultList();
  }
}
