ALTER TABLE answer_attempt
  ADD CONSTRAINT chk_answer_attempt_status
  CHECK (status IN ('PROCESSING','COMPLETED','FAILED'));
