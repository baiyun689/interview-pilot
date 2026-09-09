package interview.pilot.recruitment.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import interview.pilot.knowledge.indexing.KnowledgeRevisionReference;
import interview.pilot.recruitment.infrastructure.HiringStore;

@Component
public class HiringKnowledgeReferences implements KnowledgeRevisionReference {
  private final HiringStore store;
  public HiringKnowledgeReferences(HiringStore store) { this.store = store; }
  @Override @Transactional(readOnly = true)
  public boolean references(UUID documentId, int revision) {
    return store.one(Long.class, "select count(r) from HiringKnowledgeReference r where documentId=?1 and indexRevision=?2 and (expiresAt is null or expiresAt>?3)",
        documentId, revision, Instant.now()).orElse(0L) > 0;
  }
}
