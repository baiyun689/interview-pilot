package interview.pilot.knowledge.indexing;

import java.util.UUID;

/** Additional consumers that pin an immutable document revision while preparing an assessment. */
public interface KnowledgeRevisionReference {
  boolean references(UUID documentId, int revision);
}
