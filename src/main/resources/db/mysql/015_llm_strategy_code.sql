-- 已有协议配置转为稳定策略编码；只更新路由配置，不读取或修改凭据。
SET NAMES utf8mb4;
ALTER TABLE geo_llm_channel ADD COLUMN strategy_code VARCHAR(100) NULL COMMENT '已注册调用策略编码，协议由策略推导';
UPDATE geo_llm_channel SET strategy_code = CASE
  WHEN protocol_type = 'MOCK' THEN 'mock'
  WHEN protocol_type = 'OPENAI_RESPONSES' AND LOWER(TRIM(provider_code)) = 'openai' THEN 'openai_responses'
  WHEN protocol_type = 'OPENAI_RESPONSES' AND LOWER(TRIM(provider_code)) IN ('doubao','volcengine','ark') THEN 'doubao_responses'
  WHEN protocol_type = 'OPENAI_RESPONSES' THEN 'compatible_responses'
  WHEN protocol_type = 'OPENAI_CHAT_COMPLETIONS' AND LOWER(TRIM(provider_code)) = 'openai' THEN 'openai_chat_completions'
  WHEN protocol_type = 'OPENAI_CHAT_COMPLETIONS' THEN 'compatible_chat_completions'
  ELSE NULL END;
-- 未知旧协议会使 NOT NULL 校验失败，保留旧列供排查，不能静默改成另一种调用。
ALTER TABLE geo_llm_channel MODIFY COLUMN strategy_code VARCHAR(100) NOT NULL COMMENT '已注册调用策略编码，协议由策略推导';
ALTER TABLE geo_llm_channel DROP COLUMN protocol_type;
