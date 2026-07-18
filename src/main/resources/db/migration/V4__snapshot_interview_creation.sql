-- The new project has no legacy interview runtime data. Refuse to invent immutable
-- provider/model/plan/question snapshots if a V1-V3 database already contains interviews.
CREATE TEMPORARY TABLE v4_empty_interview_precondition (
  message VARCHAR(128) NOT NULL PRIMARY KEY
);

INSERT INTO v4_empty_interview_precondition (message)
VALUES ('V4_REQUIRES_EMPTY_INTERVIEW_TABLES');

INSERT INTO v4_empty_interview_precondition (message)
SELECT 'V4_REQUIRES_EMPTY_INTERVIEW_TABLES'
FROM interview_session
LIMIT 1;

INSERT INTO v4_empty_interview_precondition (message)
SELECT 'V4_REQUIRES_EMPTY_INTERVIEW_TABLES'
FROM interview_turn
LIMIT 1;

DROP TEMPORARY TABLE v4_empty_interview_precondition;

ALTER TABLE interview_session
  ADD COLUMN total_turn_budget INT NOT NULL DEFAULT 5 AFTER current_turn_no,
  ADD COLUMN provider_id VARCHAR(64) NOT NULL AFTER total_turn_budget,
  ADD COLUMN model_name VARCHAR(128) NOT NULL AFTER provider_id,
  ADD COLUMN plan_snapshot JSON NOT NULL AFTER model_name;

ALTER TABLE interview_turn
  ADD COLUMN target_competency VARCHAR(100) NOT NULL AFTER question_text;
