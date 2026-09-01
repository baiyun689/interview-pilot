-- This release intentionally does not migrate legacy interview data.
-- Reset the development database volume before applying it.
DROP TABLE IF EXISTS answer_attempt;
DROP TABLE IF EXISTS interview_report;
DROP TABLE IF EXISTS interview_turn;
DROP TABLE IF EXISTS interview_question_card;
DROP TABLE IF EXISTS interview_knowledge_base;
DROP TABLE IF EXISTS interview_session;
DROP TABLE IF EXISTS job_profile;

CREATE TABLE interview_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_account_id BIGINT NOT NULL,
  session_id CHAR(36) NOT NULL,
  resume_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  interview_size VARCHAR(16) NOT NULL,
  job_source_type VARCHAR(16) NOT NULL,
  job_title VARCHAR(200) NOT NULL,
  current_turn_no INT NOT NULL DEFAULT 0,
  total_turn_budget INT NOT NULL,
  provider_id VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  brief_snapshot JSON NOT NULL,
  knowledge_scope_snapshot JSON NULL,
  safe_error VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_session_session_id UNIQUE (session_id),
  CONSTRAINT fk_interview_session_user FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  CONSTRAINT fk_interview_session_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  INDEX idx_interview_session_owner_created (user_account_id, created_at),
  INDEX idx_interview_session_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_question_card (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  phase VARCHAR(32) NOT NULL,
  phase_sequence INT NOT NULL,
  topic VARCHAR(60) NOT NULL,
  question_text LONGTEXT NOT NULL,
  focus_points JSON NOT NULL,
  grounding_mode VARCHAR(32) NOT NULL,
  rag_status VARCHAR(32) NOT NULL,
  rag_context_snapshot JSON NOT NULL,
  source_ids JSON NOT NULL,
  follow_up_quota INT NOT NULL,
  fallback_follow_up VARCHAR(160) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uq_question_card_phase_sequence UNIQUE (session_id, phase, phase_sequence),
  CONSTRAINT fk_question_card_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT chk_question_card_quota CHECK (follow_up_quota BETWEEN 0 AND 2),
  INDEX idx_question_card_session_phase (session_id, phase, phase_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_turn (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  turn_no INT NOT NULL,
  phase VARCHAR(32) NOT NULL,
  question_type VARCHAR(32) NOT NULL,
  source_card_id BIGINT NOT NULL,
  request_id CHAR(36) NULL,
  status VARCHAR(32) NOT NULL,
  question_text LONGTEXT NOT NULL,
  answer_text LONGTEXT NULL,
  input_mode VARCHAR(16) NULL,
  processing_error VARCHAR(255) NULL,
  asked_at TIMESTAMP(6) NOT NULL,
  answered_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_turn_session_turn UNIQUE (session_id, turn_no),
  CONSTRAINT uq_interview_turn_request_id UNIQUE (request_id),
  CONSTRAINT fk_interview_turn_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_interview_turn_source_card FOREIGN KEY (source_card_id) REFERENCES interview_question_card(id),
  INDEX idx_interview_turn_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
  CONSTRAINT fk_answer_attempt_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_answer_attempt_turn FOREIGN KEY (turn_id) REFERENCES interview_turn(id),
  INDEX idx_answer_attempt_session_turn (session_id, turn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id CHAR(36) NOT NULL,
  session_id BIGINT NOT NULL,
  overall_score INT NOT NULL,
  summary_text LONGTEXT NOT NULL,
  report_snapshot JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_report_report_id UNIQUE (report_id),
  CONSTRAINT uq_interview_report_session_id UNIQUE (session_id),
  CONSTRAINT fk_interview_report_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT chk_interview_report_score CHECK (overall_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_knowledge_base (
  session_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  PRIMARY KEY(session_id, knowledge_base_id),
  CONSTRAINT fk_interview_kb_session FOREIGN KEY(session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_interview_kb_base FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
