ALTER TABLE async_task
  ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 AFTER attempt_count,
  ADD COLUMN last_error TEXT NULL AFTER last_published_at;
