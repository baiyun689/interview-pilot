-- M2: asynchronous per-turn answer evaluation. The structured AnswerEvaluation snapshot is
-- produced after the answer completes (outbox task + MQ) and never blocks the next question.
-- Historical turns are backfilled to NOT_REQUIRED by the NOT NULL default.
ALTER TABLE interview_turn
  ADD COLUMN answer_evaluation JSON NULL AFTER answer_text,
  ADD COLUMN eval_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER answer_evaluation;
