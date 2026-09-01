-- Voice interview: session mode snapshot columns, input mode backfill, and the
-- voice recording / question speech tables with their async lifecycles.

ALTER TABLE interview_session
  ADD COLUMN interview_mode VARCHAR(16) NOT NULL DEFAULT 'TEXT'
    AFTER interview_size,
  ADD COLUMN voice_snapshot JSON NULL
    AFTER brief_snapshot;

UPDATE interview_turn SET input_mode = 'TEXT' WHERE input_mode IS NULL;

ALTER TABLE interview_turn
  MODIFY input_mode VARCHAR(16) NOT NULL DEFAULT 'TEXT';

CREATE TABLE voice_recording (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recording_id CHAR(36) NOT NULL,
  upload_request_id CHAR(36) NOT NULL,
  user_account_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  turn_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  storage_key VARCHAR(512) NULL,
  content_type VARCHAR(80) NULL,
  size_bytes BIGINT NULL,
  duration_millis BIGINT NULL,
  sha256 CHAR(64) NULL,
  provider_id VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  provider_request_id VARCHAR(128) NULL,
  raw_transcript LONGTEXT NULL,
  attached_answer_request_id CHAR(36) NULL,
  execution_epoch BIGINT NOT NULL DEFAULT 0,
  safe_error VARCHAR(255) NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_voice_recording_id (recording_id),
  UNIQUE KEY uq_voice_upload_request (upload_request_id),
  KEY idx_voice_recording_turn (session_id, turn_id, created_at),
  KEY idx_voice_recording_expiry (status, expires_at),
  CONSTRAINT fk_voice_recording_user FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  CONSTRAINT fk_voice_recording_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_voice_recording_turn FOREIGN KEY (turn_id) REFERENCES interview_turn(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_speech (
  id BIGINT NOT NULL AUTO_INCREMENT,
  speech_id CHAR(36) NOT NULL,
  user_account_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  turn_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  text_sha256 CHAR(64) NOT NULL,
  storage_key VARCHAR(512) NULL,
  content_type VARCHAR(80) NULL,
  size_bytes BIGINT NULL,
  duration_millis BIGINT NULL,
  provider_id VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  voice_name VARCHAR(128) NOT NULL,
  provider_request_id VARCHAR(128) NULL,
  execution_epoch BIGINT NOT NULL DEFAULT 0,
  safe_error VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_question_speech_id (speech_id),
  UNIQUE KEY uq_question_speech_turn (turn_id),
  CONSTRAINT fk_question_speech_user FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  CONSTRAINT fk_question_speech_session FOREIGN KEY (session_id) REFERENCES interview_session(id),
  CONSTRAINT fk_question_speech_turn FOREIGN KEY (turn_id) REFERENCES interview_turn(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
