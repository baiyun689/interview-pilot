ALTER TABLE resume
  ADD COLUMN evaluation_snapshot JSON NULL AFTER skills_snapshot;
