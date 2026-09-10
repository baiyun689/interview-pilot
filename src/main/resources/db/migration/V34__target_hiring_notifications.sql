ALTER TABLE hiring_notification
  MODIFY COLUMN invitation_id BIGINT NULL,
  ADD COLUMN application_id BIGINT NULL AFTER invitation_id;

UPDATE hiring_notification n
JOIN hiring_interview_invitation i ON i.id = n.invitation_id
SET n.application_id = i.application_id
WHERE n.application_id IS NULL;

ALTER TABLE hiring_notification
  MODIFY COLUMN application_id BIGINT NOT NULL,
  ADD CONSTRAINT fk_notification_application
    FOREIGN KEY (application_id) REFERENCES hiring_application(id),
  ADD KEY idx_notification_application(application_id);
