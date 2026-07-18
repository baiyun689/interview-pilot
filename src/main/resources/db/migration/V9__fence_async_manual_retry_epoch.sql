ALTER TABLE async_task
  ADD COLUMN execution_epoch INT NOT NULL DEFAULT 0 AFTER attempt_count;

