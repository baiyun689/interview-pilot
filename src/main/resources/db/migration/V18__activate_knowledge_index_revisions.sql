ALTER TABLE knowledge_document
  ADD COLUMN active_index_revision INT NOT NULL DEFAULT 0 AFTER index_revision;

UPDATE knowledge_document
SET active_index_revision = index_revision
WHERE status = 'READY';

CREATE INDEX idx_knowledge_document_active_revision
  ON knowledge_document(document_id, active_index_revision);
