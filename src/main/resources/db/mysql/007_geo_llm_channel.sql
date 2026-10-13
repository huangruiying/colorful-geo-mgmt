-- 基础配置：共享模型渠道；凭据仅供服务端读取，不通过查询接口返回。
CREATE TABLE IF NOT EXISTS geo_llm_channel (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '渠道 ID',
  channel_code VARCHAR(100) NOT NULL COMMENT '唯一渠道编码',
  channel_name VARCHAR(200) NOT NULL COMMENT '渠道名称',
  provider_code VARCHAR(100) NOT NULL COMMENT '供应商标识',
  strategy_bean_name VARCHAR(100) NOT NULL COMMENT 'Spring 实际注册的策略 Bean 名',
  base_url VARCHAR(2048) NULL COMMENT '接口根地址',
  model_name VARCHAR(200) NULL COMMENT '模型标识',
  api_key TEXT NULL COMMENT '服务端调用密钥，不返回页面',
  temperature DOUBLE NOT NULL DEFAULT 0.3 COMMENT '生成随机性',
  max_tokens INT NOT NULL DEFAULT 1400 COMMENT '输出 token 上限',
  timeout_seconds INT NOT NULL DEFAULT 60 COMMENT '请求超时秒数',
  ssl_verify BOOLEAN NOT NULL DEFAULT TRUE COMMENT '校验证书',
  score INT NOT NULL DEFAULT 0 COMMENT '默认优先级，越大越优先',
  enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_llm_channel_code (channel_code),
  KEY idx_llm_channel_priority (enabled, score, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='共享大模型调用渠道';
