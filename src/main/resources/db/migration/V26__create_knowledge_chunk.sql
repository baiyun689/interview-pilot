-- Lexical mirror of the Qdrant chunks: every embedded chunk is also persisted here so a
-- FULLTEXT (ngram) retrieval source can provide keyword candidates for hybrid retrieval.
-- point_id mirrors the deterministic Qdrant point id, letting both sources de-duplicate / fuse
-- on the same key; scope/version columns mirror the Qdrant metadata filters one-to-one.
CREATE TABLE knowledge_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT,
  point_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  knowledge_base_id CHAR(36) NOT NULL,
  document_id CHAR(36) NOT NULL,
  index_revision INT NOT NULL,
  chunk_index INT NOT NULL,
  section VARCHAR(64) NOT NULL DEFAULT '',
  filename VARCHAR(255) NOT NULL DEFAULT '',
  content TEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_chunk_point UNIQUE (point_id),
  CONSTRAINT uq_knowledge_chunk_doc_rev_idx
    UNIQUE (document_id, index_revision, chunk_index),
  CONSTRAINT chk_knowledge_chunk_revision CHECK (index_revision >= 1),
  CONSTRAINT chk_knowledge_chunk_index CHECK (chunk_index >= 0),
  INDEX idx_knowledge_chunk_scope (user_id, knowledge_base_id),
  INDEX idx_knowledge_chunk_doc_rev (document_id, index_revision),
  FULLTEXT KEY ft_knowledge_chunk_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
