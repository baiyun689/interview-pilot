ALTER TABLE ai_setting
  DROP COLUMN base_url,
  DROP COLUMN encrypted_api_key,
  DROP COLUMN options_snapshot,
  DROP COLUMN model_name,
  DROP COLUMN enabled,
  CHANGE COLUMN provider provider_id VARCHAR(64) NOT NULL;

INSERT INTO ai_setting (id, singleton_guard, setting_key, provider_id)
VALUES (1, TRUE, 'default-provider', 'dashscope');
