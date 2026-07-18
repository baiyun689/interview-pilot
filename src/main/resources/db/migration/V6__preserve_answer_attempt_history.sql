CREATE TABLE answer_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id CHAR(36) NOT NULL,
  session_id BIGINT NOT NULL,
  turn_id BIGINT NOT NULL,
  answer_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  result_snapshot JSON NULL,
  safe_error VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_answer_attempt_request_id UNIQUE (request_id),
  CONSTRAINT fk_answer_attempt_session FOREIGN KEY (session_id) REFERENCES interview_session (id),
  CONSTRAINT fk_answer_attempt_turn FOREIGN KEY (turn_id) REFERENCES interview_turn (id),
  INDEX idx_answer_attempt_session_turn (session_id, turn_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO answer_attempt (
  request_id, session_id, turn_id, answer_hash, status, result_snapshot, safe_error)
SELECT request_id, session_id, id, SHA2(TRIM(answer_text), 256), status,
       evaluation_snapshot, processing_error
FROM interview_turn
WHERE request_id IS NOT NULL
  AND answer_text IS NOT NULL
  AND status IN ('PROCESSING', 'COMPLETED', 'FAILED');
