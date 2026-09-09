CREATE TABLE hiring_scheme (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  definition JSON NOT NULL,
  published_revision INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_hiring_scheme_org (organization_id, id),
  FOREIGN KEY (organization_id, job_id) REFERENCES hiring_job(organization_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_scheme_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  scheme_id BIGINT NOT NULL,
  revision INT NOT NULL,
  name VARCHAR(120) NOT NULL,
  definition JSON NOT NULL,
  provider_id VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  published_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_hiring_scheme_version (scheme_id, revision),
  FOREIGN KEY (scheme_id) REFERENCES hiring_scheme(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hiring_work (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  application_id BIGINT NULL,
  submission_no INT NULL,
  task_id BIGINT NOT NULL UNIQUE,
  kind VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,
  input_snapshot JSON NOT NULL,
  output_snapshot JSON NULL,
  error VARCHAR(255) NULL,
  lease_token VARCHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  INDEX ix_hiring_work_due (status, lease_until),
  FOREIGN KEY (organization_id, job_id) REFERENCES hiring_job(organization_id, id),
  FOREIGN KEY (application_id) REFERENCES hiring_application(id),
  FOREIGN KEY (task_id) REFERENCES async_task(id),
  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
