ALTER TABLE resume MODIFY COLUMN user_account_id BIGINT NOT NULL;
ALTER TABLE job_profile MODIFY COLUMN user_account_id BIGINT NOT NULL;
ALTER TABLE interview_session MODIFY COLUMN user_account_id BIGINT NOT NULL;
ALTER TABLE async_task MODIFY COLUMN user_account_id BIGINT NOT NULL;
