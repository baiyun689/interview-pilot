CREATE TABLE hiring_organization (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_membership (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  user_account_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  active BOOLEAN NOT NULL,
  UNIQUE KEY uq_hiring_member (organization_id, user_account_id),
  FOREIGN KEY (organization_id) REFERENCES hiring_organization(id),
  FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  CHECK (role IN ('ADMIN', 'RECRUITER', 'INTERVIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_member_invitation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  email VARCHAR(320) NOT NULL,
  role VARCHAR(20) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP(6) NOT NULL,
  accepted_at TIMESTAMP(6) NULL,
  FOREIGN KEY (organization_id) REFERENCES hiring_organization(id),
  CHECK (role IN ('ADMIN', 'RECRUITER', 'INTERVIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_job (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description LONGTEXT NOT NULL,
  location VARCHAR(120) NOT NULL,
  employment_type VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,
  published_revision INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_hiring_job_org (organization_id, id),
  INDEX ix_hiring_jobs (organization_id, status, created_at),
  FOREIGN KEY (organization_id) REFERENCES hiring_organization(id),
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_job_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  revision INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description LONGTEXT NOT NULL,
  location VARCHAR(120) NOT NULL,
  employment_type VARCHAR(40) NOT NULL,
  published_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_hiring_job_revision (job_id, revision),
  FOREIGN KEY (job_id) REFERENCES hiring_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_job_assignment (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  user_account_id BIGINT NOT NULL,
  UNIQUE KEY uq_hiring_job_assignment (job_id, user_account_id),
  FOREIGN KEY (organization_id, job_id) REFERENCES hiring_job(organization_id, id),
  FOREIGN KEY (organization_id, user_account_id)
    REFERENCES hiring_membership(organization_id, user_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_resume_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_account_id BIGINT NOT NULL,
  source_resume_id BIGINT NULL,
  filename VARCHAR(255) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  parsed_text LONGTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  FOREIGN KEY (user_account_id) REFERENCES user_account(id),
  FOREIGN KEY (source_resume_id) REFERENCES resume(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_application (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  candidate_id BIGINT NOT NULL,
  job_revision INT NOT NULL,
  resume_revision_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  submission_no INT NOT NULL DEFAULT 1,
  submitted_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_hiring_application (job_id, candidate_id),
  INDEX ix_hiring_applications (organization_id, job_id, status, submitted_at),
  INDEX ix_hiring_candidate (candidate_id, submitted_at),
  FOREIGN KEY (organization_id, job_id) REFERENCES hiring_job(organization_id, id),
  FOREIGN KEY (job_id, job_revision) REFERENCES hiring_job_revision(job_id, revision),
  FOREIGN KEY (candidate_id) REFERENCES user_account(id),
  FOREIGN KEY (resume_revision_id) REFERENCES hiring_resume_revision(id),
  CHECK (status IN ('SUBMITTED', 'IN_PROCESS', 'FINISHED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_application_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  application_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  submission_no INT NOT NULL,
  job_revision INT NOT NULL,
  resume_revision_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  FOREIGN KEY (application_id) REFERENCES hiring_application(id),
  FOREIGN KEY (actor_id) REFERENCES user_account(id),
  FOREIGN KEY (resume_revision_id) REFERENCES hiring_resume_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  resource_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX ix_hiring_audit (organization_id, created_at),
  FOREIGN KEY (organization_id) REFERENCES hiring_organization(id),
  FOREIGN KEY (actor_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
