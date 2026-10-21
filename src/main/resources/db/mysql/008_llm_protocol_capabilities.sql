-- 已有库执行一次：把搜索能力归入协议类型，然后移除重复字段。
UPDATE geo_llm_channel
SET protocol_type = 'OPENAI_RESPONSES'
WHERE protocol_type = 'OPENAI_COMPATIBLE' AND web_search_supported = TRUE;
ALTER TABLE geo_llm_channel DROP COLUMN web_search_supported;
