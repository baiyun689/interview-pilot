ALTER TABLE interview_session
  ADD COLUMN hiring_invitation_id BIGINT NULL,
  ADD COLUMN execution_plan JSON NULL,
  ADD COLUMN answer_deadline DATETIME(6) NULL,
  ADD CONSTRAINT uk_interview_hiring_invitation UNIQUE (hiring_invitation_id),
  ADD CONSTRAINT fk_interview_hiring_invitation FOREIGN KEY (hiring_invitation_id) REFERENCES hiring_interview_invitation(id),
  ADD INDEX idx_interview_deadline (status, answer_deadline);
