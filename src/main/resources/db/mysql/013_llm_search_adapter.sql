-- Chat Completions 适配改名；普通 Responses 不等于联网搜索。
SET NAMES utf8mb4;
UPDATE geo_llm_channel SET protocol_type = 'OPENAI_CHAT_COMPLETIONS' WHERE protocol_type = 'OPENAI_STANDARD';
