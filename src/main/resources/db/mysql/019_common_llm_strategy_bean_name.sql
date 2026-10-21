-- 通用策略更名：已有渠道升级时执行，保留其他策略、连接配置及历史快照。
-- 执行前先结束使用旧策略的未完成诊断；快照保留原名，迁移后需重新创建这些任务。
SET NAMES utf8mb4;
UPDATE geo_llm_channel
SET strategy_bean_name = CASE strategy_bean_name
    WHEN 'compatibleChatCompletionsApiStrategy' THEN 'commonChatCompletionsApiStrategy'
    WHEN 'compatibleResponsesApiStrategy' THEN 'commonResponsesApiStrategy'
    ELSE strategy_bean_name END
WHERE strategy_bean_name IN ('compatibleChatCompletionsApiStrategy', 'compatibleResponsesApiStrategy');
