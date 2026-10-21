-- 协议只描述交互格式；原 OpenAI 搜索渠道沿用 openai 供应商的搜索参数。
SET NAMES utf8mb4;
UPDATE geo_llm_channel SET protocol_type = 'OPENAI_RESPONSES' WHERE protocol_type = 'OPENAI_WEB_SEARCH';
