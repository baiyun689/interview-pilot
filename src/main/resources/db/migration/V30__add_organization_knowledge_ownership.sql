ALTER TABLE knowledge_base MODIFY user_account_id BIGINT NULL,
  ADD COLUMN organization_id BIGINT NULL,
  ADD CONSTRAINT fk_knowledge_base_organization FOREIGN KEY (organization_id) REFERENCES hiring_organization(id),
  ADD CONSTRAINT chk_knowledge_base_owner CHECK ((user_account_id IS NULL) <> (organization_id IS NULL)),
  ADD INDEX ix_knowledge_base_organization (organization_id, created_at);

ALTER TABLE knowledge_chunk MODIFY user_id CHAR(36) NULL,
  ADD COLUMN organization_id BIGINT NULL,
  ADD CONSTRAINT chk_knowledge_chunk_owner CHECK ((user_id IS NULL) <> (organization_id IS NULL)),
  ADD INDEX ix_knowledge_chunk_organization (organization_id, knowledge_base_id, document_id, index_revision);

ALTER TABLE hiring_work MODIFY job_id BIGINT NULL;

CREATE TABLE hiring_knowledge_reference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  index_revision INT NOT NULL,
  reference_key VARCHAR(160) NOT NULL,
  expires_at TIMESTAMP(6) NULL,
  UNIQUE KEY uq_hiring_knowledge_reference (document_id, index_revision, reference_key),
  INDEX ix_hiring_knowledge_reference_expiry (document_id, index_revision, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE hiring_scheme_revision ADD COLUMN knowledge_scope_snapshot JSON NULL,
  ADD COLUMN retired BOOLEAN NOT NULL DEFAULT FALSE;
