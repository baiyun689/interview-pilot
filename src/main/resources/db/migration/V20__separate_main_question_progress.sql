ALTER TABLE interview_session
  CHANGE COLUMN total_turn_budget total_main_question_count INT NOT NULL,
  ADD COLUMN current_main_question_no INT NOT NULL DEFAULT 0 AFTER current_turn_no;

UPDATE interview_session session
SET current_main_question_no = (
  SELECT COUNT(*)
  FROM interview_turn turn_row
  WHERE turn_row.session_id = session.id
    AND turn_row.turn_no <= session.current_turn_no
    AND turn_row.question_type IN ('SELF_INTRODUCTION', 'MAIN')
);
