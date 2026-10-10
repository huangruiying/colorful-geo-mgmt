-- 内容优化记录与逐平台发布记录；仅用于初始化空库。
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
    publish_status TINYINT UNSIGNED NOT NULL COMMENT '0初始化 1预发布中 2预发布成功 3实际发布中 4实际发布成功 5预发布失败 6实际发布失败 7已取消 8草稿已创建待核对(自动化无法确认落库)',
    remote_content_id VARCHAR(255) NULL COMMENT '平台明确返回的草稿或文章ID',
    draft_url VARCHAR(2048) NULL COMMENT '平台草稿编辑链接',
    published_url VARCHAR(2048) NULL COMMENT '公开发布链接',
    failure_reason VARCHAR(500) NULL COMMENT '不含凭据和正文的失败原因',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_platform (content_optimization_record_id, platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容逐平台发布记录';
