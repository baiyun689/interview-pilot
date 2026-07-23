CREATE TABLE user_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id CHAR(36) NOT NULL,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_user_account_user_id UNIQUE (user_id),
  CONSTRAINT uq_user_account_email UNIQUE (email),
  CONSTRAINT chk_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO user_account(user_id, email, password_hash, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000001',
        'legacy-demo@invalid.local', '!', 'Legacy Demo', 'DISABLED');

ALTER TABLE resume ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE job_profile ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE interview_session ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE async_task ADD COLUMN user_account_id BIGINT NULL AFTER id;

UPDATE resume SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE job_profile SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE interview_session SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE async_task SET user_account_id = 1 WHERE user_account_id IS NULL;

ALTER TABLE resume DROP INDEX uq_resume_content_hash;

ALTER TABLE resume ADD CONSTRAINT fk_resume_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE job_profile ADD CONSTRAINT fk_job_profile_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE interview_session ADD CONSTRAINT fk_interview_session_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE async_task ADD CONSTRAINT fk_async_task_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);

CREATE UNIQUE INDEX uq_resume_user_hash ON resume(user_account_id, content_hash);
CREATE INDEX idx_resume_user_created ON resume(user_account_id, created_at);
CREATE INDEX idx_interview_user_created ON interview_session(user_account_id, created_at);
CREATE INDEX idx_async_task_user_task ON async_task(user_account_id, task_id);
