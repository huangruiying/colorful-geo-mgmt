-- 项目基础档案、内容优化记录与逐平台发布记录；仅用于初始化空库。
CREATE DATABASE IF NOT EXISTS colorful_geo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE colorful_geo;

CREATE TABLE IF NOT EXISTS content_optimization_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内容记录ID',
    title VARCHAR(200) NOT NULL COMMENT '内容标题',
    original_content LONGTEXT NOT NULL COMMENT '优化前正文',
    optimized_content LONGTEXT NOT NULL COMMENT '模型返回的优化正文',
    optimization_preference VARCHAR(1000) NULL COMMENT '优化偏好',
    applied_strategies JSON NOT NULL COMMENT '实际使用的优化策略',
    optimization_warnings JSON NOT NULL COMMENT '发布前提示',
    citations JSON NOT NULL COMMENT '模型返回的结构化引用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='GEO内容优化记录';

CREATE TABLE IF NOT EXISTS content_publish_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '发布记录ID',
    content_optimization_record_id BIGINT UNSIGNED NOT NULL COMMENT '所属内容优化记录ID',
    platform_type VARCHAR(64) NOT NULL COMMENT '平台枚举名称',
    publication_title VARCHAR(200) NOT NULL COMMENT '用户确认时的标题快照',
    publication_content LONGTEXT NOT NULL COMMENT '用户确认时的正文快照',
    publish_status TINYINT UNSIGNED NOT NULL COMMENT '0初始化 1预发布中 2预发布成功 3实际发布中 4实际发布成功 5预发布失败 6实际发布失败 7已取消',
    remote_content_id VARCHAR(255) NULL COMMENT '平台明确返回的草稿或文章ID',
    draft_url VARCHAR(2048) NULL COMMENT '平台草稿编辑链接',
    published_url VARCHAR(2048) NULL COMMENT '公开发布链接',
    failure_reason VARCHAR(500) NULL COMMENT '不含凭据和正文的失败原因',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_platform (content_optimization_record_id, platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容逐平台发布记录';

-- 增量添加项目基础档案；不会重建或修改已有业务表。
CREATE TABLE IF NOT EXISTS geo_project_info (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '项目ID，供任务关联',
    name VARCHAR(200) NOT NULL COMMENT '项目或产品名称',
    website_url VARCHAR(2048) NOT NULL COMMENT '官网或资料URL',
    industry VARCHAR(200) NOT NULL COMMENT '所属行业或品类',
    core_features TEXT NOT NULL COMMENT '核心功能',
    target_audience TEXT NOT NULL COMMENT '目标用户群体',
    aliases JSON NOT NULL COMMENT '别名或品牌名列表',
    competitors JSON NOT NULL COMMENT '主要竞品列表',
    target_market VARCHAR(200) NULL COMMENT '目标市场',
    target_language VARCHAR(100) NULL COMMENT '目标语言',
    selling_points TEXT NULL COMMENT '核心卖点',
    pricing TEXT NULL COMMENT '价格说明',
    keywords JSON NOT NULL COMMENT '目标关键词列表',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目基础档案';
-- 基础配置：共享模型渠道；凭据仅供服务端读取，不通过查询接口返回。
CREATE TABLE IF NOT EXISTS geo_llm_channel (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '渠道 ID',
  channel_code VARCHAR(100) NOT NULL COMMENT '唯一渠道编码',
  channel_name VARCHAR(200) NOT NULL COMMENT '渠道名称',
  provider_code VARCHAR(100) NOT NULL COMMENT '供应商标识',
  protocol_type VARCHAR(40) NOT NULL COMMENT '调用协议',
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
