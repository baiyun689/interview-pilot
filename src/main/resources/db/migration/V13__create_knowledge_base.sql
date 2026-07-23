CREATE TABLE knowledge_base (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_account_id BIGINT NOT NULL,
  knowledge_base_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_base_knowledge_base_id UNIQUE (knowledge_base_id),
  CONSTRAINT fk_knowledge_base_user
    FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  CONSTRAINT chk_knowledge_base_status CHECK (status IN ('ACTIVE', 'DELETING')),
  INDEX idx_knowledge_base_user_created (user_account_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_document (
  id BIGINT NOT NULL AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  document_id CHAR(36) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  storage_key VARCHAR(512) NOT NULL,
  parsed_text LONGTEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  index_revision INT NOT NULL DEFAULT 0,
  embedding_snapshot JSON NULL,
  chunk_count INT NOT NULL DEFAULT 0,
  failure_reason TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_document_document_id UNIQUE (document_id),
  CONSTRAINT uq_knowledge_document_base_hash UNIQUE (knowledge_base_id, content_hash),
  CONSTRAINT fk_knowledge_document_base
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
  CONSTRAINT chk_knowledge_document_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'DELETING')),
  CONSTRAINT chk_knowledge_document_index_revision CHECK (index_revision >= 0),
  CONSTRAINT chk_knowledge_document_chunk_count CHECK (chunk_count >= 0),
  INDEX idx_knowledge_document_user_base_status (knowledge_base_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
