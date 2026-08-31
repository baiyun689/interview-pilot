ALTER TABLE interview_session
  ADD COLUMN question_deck_snapshot JSON NULL AFTER knowledge_scope_snapshot;

UPDATE interview_session
SET question_deck_snapshot = JSON_OBJECT('questions', JSON_ARRAY())
WHERE question_deck_snapshot IS NULL;

ALTER TABLE interview_session
  MODIFY COLUMN question_deck_snapshot JSON NOT NULL;
