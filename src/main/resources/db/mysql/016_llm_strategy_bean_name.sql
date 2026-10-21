-- 策略直接使用 Spring Bean 名，保留渠道及凭据；只执行于已有 strategy_code 的旧表。
SET NAMES utf8mb4;
ALTER TABLE geo_llm_channel RENAME COLUMN strategy_code TO strategy_bean_name;
UPDATE geo_llm_channel SET strategy_bean_name = CASE strategy_bean_name
  WHEN 'compatible_chat_completions' THEN 'compatibleChatCompletionsApiStrategy'
  WHEN 'compatible_responses' THEN 'compatibleResponsesApiStrategy'
  WHEN 'openai_chat_completions' THEN 'openAiChatCompletionsApiStrategy'
  WHEN 'openai_responses' THEN 'openAiResponsesApiStrategy'
  WHEN 'doubao_responses' THEN 'doubaoResponsesApiStrategy'
  WHEN 'mock' THEN 'mockLlmStrategy'
  ELSE strategy_bean_name END;
ALTER TABLE geo_llm_channel MODIFY COLUMN strategy_bean_name VARCHAR(100) NOT NULL COMMENT 'Spring 实际注册的策略 Bean 名';
