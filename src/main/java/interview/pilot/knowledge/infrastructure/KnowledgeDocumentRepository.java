package interview.pilot.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface KnowledgeDocumentRepository {
  KnowledgeDocumentEntity save(KnowledgeDocumentEntity document);

  List<KnowledgeDocumentEntity> findReadyByKnowledgeBaseIdsAndUserAccountId(
      Collection<UUID> knowledgeBaseIds, Long userAccountId);
}
