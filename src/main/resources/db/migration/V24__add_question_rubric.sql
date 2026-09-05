-- Two-stage question preparation: each main-question card gains its canonical knowledge point,
-- the keywords used for its question-scoped retrieval, and a frozen, traceable rubric. These are
-- preparation-time immutable columns; historical cards simply keep them NULL (evaluation then
-- falls back to focus points).

ALTER TABLE interview_question_card
  ADD COLUMN knowledge_point VARCHAR(128) NULL AFTER focus_points,
  ADD COLUMN retrieval_keywords JSON NULL AFTER knowledge_point,
  ADD COLUMN rubric JSON NULL AFTER source_ids;
