CREATE TABLE resume (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resume_id CHAR(36) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  parsed_text LONGTEXT NULL,
  skills_snapshot JSON NULL,
  status VARCHAR(32) NOT NULL,
  failure_reason TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_resume_resume_id UNIQUE (resume_id),
  CONSTRAINT uq_resume_content_hash UNIQUE (content_hash),
  INDEX idx_resume_status_created_at (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE job_profile (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id CHAR(36) NOT NULL,
  title VARCHAR(255) NOT NULL,
  description_text LONGTEXT NOT NULL,
  requirements_snapshot JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_job_profile_job_id UNIQUE (job_id),
  INDEX idx_job_profile_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interview_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id CHAR(36) NOT NULL,
  resume_id BIGINT NOT NULL,
  job_profile_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  current_turn_no INT NOT NULL DEFAULT 0,
  context_snapshot JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_session_session_id UNIQUE (session_id),
  CONSTRAINT fk_interview_session_resume
    FOREIGN KEY (resume_id) REFERENCES resume (id),
  CONSTRAINT fk_interview_session_job_profile
    FOREIGN KEY (job_profile_id) REFERENCES job_profile (id),
  INDEX idx_interview_session_resume_id (resume_id),
  INDEX idx_interview_session_job_profile_id (job_profile_id),
  INDEX idx_interview_session_status_created_at (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interview_turn (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  turn_no INT NOT NULL,
  request_id CHAR(36) NOT NULL,
  status VARCHAR(32) NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  question_text LONGTEXT NOT NULL,
  answer_text LONGTEXT NULL,
  feedback_text LONGTEXT NULL,
  score DECIMAL(5, 2) NULL,
  evaluation_snapshot JSON NULL,
  asked_at TIMESTAMP(6) NOT NULL,
  answered_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_turn_session_turn UNIQUE (session_id, turn_no),
  CONSTRAINT uq_interview_turn_request_id UNIQUE (request_id),
  CONSTRAINT fk_interview_turn_session
    FOREIGN KEY (session_id) REFERENCES interview_session (id),
  INDEX idx_interview_turn_session_status (session_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interview_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id CHAR(36) NOT NULL,
  session_id BIGINT NOT NULL,
  summary_text LONGTEXT NOT NULL,
  feedback_text LONGTEXT NOT NULL,
  score_snapshot JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_interview_report_report_id UNIQUE (report_id),
  CONSTRAINT uq_interview_report_session_id UNIQUE (session_id),
  CONSTRAINT fk_interview_report_session
    FOREIGN KEY (session_id) REFERENCES interview_session (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE async_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id CHAR(36) NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  biz_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload_snapshot JSON NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_published_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_async_task_task_id UNIQUE (task_id),
  CONSTRAINT uq_async_task_type_biz_key UNIQUE (task_type, biz_key),
  INDEX idx_async_task_republish (status, last_published_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ai_setting (
  id BIGINT NOT NULL AUTO_INCREMENT,
  singleton_guard BOOLEAN NOT NULL DEFAULT TRUE,
  setting_key VARCHAR(64) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512) NULL,
  encrypted_api_key LONGTEXT NULL,
  options_snapshot JSON NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_setting_singleton_guard UNIQUE (singleton_guard),
  CONSTRAINT chk_ai_setting_singleton_guard CHECK (singleton_guard = TRUE),
  CONSTRAINT uq_ai_setting_setting_key UNIQUE (setting_key),
  INDEX idx_ai_setting_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
