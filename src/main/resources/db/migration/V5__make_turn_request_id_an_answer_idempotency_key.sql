ALTER TABLE interview_turn
  MODIFY COLUMN request_id CHAR(36) NULL,
  ADD COLUMN processing_error VARCHAR(255) NULL AFTER evaluation_snapshot;

-- Before Task 9, Hibernate generated request_id when a question was asked. Those values were
-- never client idempotency keys and must not be reinterpreted as such after an upgrade.
UPDATE interview_turn
SET request_id = NULL
WHERE status = 'ASKED' AND answer_text IS NULL;
