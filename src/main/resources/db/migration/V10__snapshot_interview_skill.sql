ALTER TABLE job_profile
  ADD COLUMN skill_snapshot JSON NULL AFTER requirements_snapshot;

UPDATE job_profile
SET skill_snapshot = JSON_OBJECT(
  'id', 'custom',
  'name', '自定义岗位',
  'description', '历史面试兼容快照',
  'group', 'CUSTOM',
  'defaultCompetencies', JSON_ARRAY(),
  'persona', '',
  'rubric', '',
  'references', JSON_ARRAY(),
  'version', 'legacy'
)
WHERE skill_snapshot IS NULL;

ALTER TABLE job_profile
  MODIFY COLUMN skill_snapshot JSON NOT NULL;
