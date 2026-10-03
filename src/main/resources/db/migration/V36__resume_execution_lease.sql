ALTER TABLE async_task
    ADD COLUMN execution_token VARCHAR(36) NULL,
    ADD COLUMN execution_lease_until DATETIME(6) NULL;
