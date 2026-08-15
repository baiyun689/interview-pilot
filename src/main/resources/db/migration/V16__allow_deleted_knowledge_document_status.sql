ALTER TABLE knowledge_document DROP CHECK chk_knowledge_document_status;

ALTER TABLE knowledge_document
  ADD CONSTRAINT chk_knowledge_document_status
  CHECK (status IN ('PENDING','PROCESSING','READY','FAILED','DELETING','DELETED'));
