-- 协议枚举改名，同步已有渠道配置；可重复执行，不改渠道凭据。
SET NAMES utf8mb4;
UPDATE geo_llm_channel SET protocol_type = 'OPENAI_STANDARD' WHERE protocol_type = 'OPENAI_COMPATIBLE';
UPDATE geo_llm_channel SET protocol_type = 'OPENAI_WEB_SEARCH' WHERE protocol_type = 'OPENAI_RESPONSES';
