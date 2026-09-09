CREATE TABLE hiring_review_assignment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, invitation_id BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 UNIQUE KEY uk_review_assignment(invitation_id, reviewer_id),
 FOREIGN KEY(invitation_id) REFERENCES hiring_interview_invitation(id), FOREIGN KEY(reviewer_id) REFERENCES user_account(id)
);
CREATE TABLE hiring_review (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, invitation_id BIGINT NOT NULL, reviewer_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL, content JSON NOT NULL, revision INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_review_author(invitation_id, reviewer_id),
 FOREIGN KEY(invitation_id) REFERENCES hiring_interview_invitation(id), FOREIGN KEY(reviewer_id) REFERENCES user_account(id)
);
CREATE TABLE hiring_review_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, review_id BIGINT NOT NULL, revision INT NOT NULL, content JSON NOT NULL,
 submitted_at DATETIME(6) NOT NULL, UNIQUE KEY uk_review_revision(review_id, revision),
 FOREIGN KEY(review_id) REFERENCES hiring_review(id)
);
CREATE TABLE hiring_feedback (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, invitation_id BIGINT NOT NULL, revision INT NOT NULL,
 review_revision_id BIGINT NOT NULL, decision VARCHAR(30) NOT NULL, feedback TEXT NOT NULL,
 published_by BIGINT NOT NULL, published_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_feedback_revision(invitation_id, revision),
 FOREIGN KEY(invitation_id) REFERENCES hiring_interview_invitation(id),
 FOREIGN KEY(review_revision_id) REFERENCES hiring_review_revision(id), FOREIGN KEY(published_by) REFERENCES user_account(id)
);
CREATE TABLE hiring_notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, invitation_id BIGINT NOT NULL, recipient_id BIGINT NOT NULL,
 event_key VARCHAR(120) NOT NULL, kind VARCHAR(30) NOT NULL, schedule_revision INT NOT NULL,
 title VARCHAR(200) NOT NULL, message TEXT NOT NULL, due_at DATETIME(6) NOT NULL,
 visible_at DATETIME(6) NULL, read_at DATETIME(6) NULL, mail_status VARCHAR(30) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL,
 lease_token VARCHAR(36) NULL, lease_until DATETIME(6) NULL, error VARCHAR(200) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_notification_event(recipient_id,event_key),
 KEY idx_notification_due(mail_status,next_attempt_at), KEY idx_notification_lease(mail_status,lease_until),
 KEY idx_notification_inbox(recipient_id,visible_at),
 FOREIGN KEY(invitation_id) REFERENCES hiring_interview_invitation(id), FOREIGN KEY(recipient_id) REFERENCES user_account(id)
);
CREATE TABLE hiring_delivery_attempt (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, notification_id BIGINT NOT NULL, attempt_no INT NOT NULL,
 status VARCHAR(30) NOT NULL, started_at DATETIME(6) NOT NULL, finished_at DATETIME(6) NULL,
 UNIQUE KEY uk_delivery_attempt(notification_id,attempt_no), FOREIGN KEY(notification_id) REFERENCES hiring_notification(id)
);
