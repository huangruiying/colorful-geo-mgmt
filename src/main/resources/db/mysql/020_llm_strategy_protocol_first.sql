-- 策略 Bean 统一按协议、厂商命名；在 019 之后执行，可重复执行。
-- 先结束旧策略的未完成诊断；历史快照不改写，升级后重新创建这些任务。
SET NAMES utf8mb4;
UPDATE geo_llm_channel
SET strategy_bean_name = CASE strategy_bean_name
    WHEN 'commonChatCompletionsApiStrategy' THEN 'chatCompletionsApiCommonStrategy'
    WHEN 'commonResponsesApiStrategy' THEN 'responsesApiCommonStrategy'
    WHEN 'openAiChatCompletionsApiStrategy' THEN 'chatCompletionsApiOpenAiStrategy'
    WHEN 'openAiResponsesApiStrategy' THEN 'responsesApiOpenAiStrategy'
    WHEN 'doubaoResponsesApiStrategy' THEN 'responsesApiDoubaoStrategy'
    ELSE strategy_bean_name END
WHERE strategy_bean_name IN (
    'commonChatCompletionsApiStrategy', 'commonResponsesApiStrategy',
    'openAiChatCompletionsApiStrategy', 'openAiResponsesApiStrategy', 'doubaoResponsesApiStrategy'
);
