ALTER TABLE interview_session
  ADD COLUMN knowledge_scope_snapshot JSON NULL AFTER context_snapshot;

ALTER TABLE interview_turn
  ADD COLUMN rag_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED'
    AFTER target_competency,
  ADD COLUMN rag_context_snapshot JSON NULL AFTER rag_status;

CREATE TABLE interview_knowledge_base (
  session_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  PRIMARY KEY(session_id, knowledge_base_id),
  CONSTRAINT fk_interview_kb_session FOREIGN KEY(session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_interview_kb_base FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
