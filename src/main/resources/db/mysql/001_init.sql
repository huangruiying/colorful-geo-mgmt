-- 项目基础档案、内容优化记录与逐平台发布记录；仅用于初始化空库。
SET NAMES utf8mb4;
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

-- Playwright 独立平台登录态，供浏览器登录管理与平台发布读取；与内容发布记录无关联。
CREATE TABLE IF NOT EXISTS platform_browser_login (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '登录记录 ID',
    platform_type VARCHAR(64) NOT NULL COMMENT '平台枚举名称，首版每个平台一个账号',
    account_name VARCHAR(255) NULL COMMENT '平台核验返回的账号名',
    login_status VARCHAR(32) NOT NULL COMMENT 'LOGGED_IN、EXPIRED、UNKNOWN',
    storage_state LONGTEXT NOT NULL COMMENT 'Playwright storageState JSON，仅服务端读取',
    last_login_at DATETIME(3) NOT NULL COMMENT '最近一次登录时间',
    last_checked_at DATETIME(3) NULL COMMENT '最近一次主动核验时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_browser_login_platform (platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立 Playwright 平台登录态';

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
  strategy_bean_name VARCHAR(100) NOT NULL COMMENT 'Spring 实际注册的策略 Bean 名',
  base_url VARCHAR(2048) NULL COMMENT '接口根地址',
  model_name VARCHAR(200) NULL COMMENT '模型标识',
  api_key TEXT NULL COMMENT '服务端调用密钥，不返回页面',
  temperature DOUBLE NULL DEFAULT NULL COMMENT '可选生成随机性，空值不发送',
  max_tokens INT NOT NULL DEFAULT 60000 COMMENT '输出 token 上限',
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

-- 诊断方案只保存配置；后续执行任务另行保存采样快照。
CREATE TABLE IF NOT EXISTS geo_diagnosis_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL COMMENT '方案名称',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '关联基础项目',
    repeat_count INT NOT NULL COMMENT '每题每渠道重复次数',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_diagnosis_plan_project (project_id),
    CONSTRAINT fk_diagnosis_plan_project FOREIGN KEY (project_id) REFERENCES geo_project_info(id),
    CONSTRAINT chk_diagnosis_plan_repeat CHECK (repeat_count BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资产诊断方案';

CREATE TABLE IF NOT EXISTS geo_diagnosis_plan_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id BIGINT UNSIGNED NOT NULL,
    question_text VARCHAR(2000) NOT NULL,
    source VARCHAR(20) NOT NULL COMMENT 'GENERATED / MANUAL',
    intent VARCHAR(30) NOT NULL COMMENT '提问目的',
    brand_scope VARCHAR(20) NOT NULL COMMENT 'NON_BRAND / BRAND / COMPARISON',
    language VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_question_order (plan_id, sort_order),
    CONSTRAINT fk_diagnosis_question_plan FOREIGN KEY (plan_id) REFERENCES geo_diagnosis_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断方案问题集合';

CREATE TABLE IF NOT EXISTS geo_diagnosis_plan_channel (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id BIGINT UNSIGNED NOT NULL,
    channel_id BIGINT NOT NULL COMMENT '被测模型渠道ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_plan_channel (plan_id, channel_id),
    CONSTRAINT fk_diagnosis_channel_plan FOREIGN KEY (plan_id) REFERENCES geo_diagnosis_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_channel_llm FOREIGN KEY (channel_id) REFERENCES geo_llm_channel(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断方案被测渠道';

-- 方案每次执行独立保存；快照仅包含非敏感配置，不保存 API Key、Cookie 或令牌。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS geo_diagnosis_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建请求唯一标识，用于防止重复提交；历史记录可为空',
    plan_id BIGINT UNSIGNED NOT NULL COMMENT '来源方案ID',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '执行时关联项目ID',
    plan_snapshot JSON NOT NULL COMMENT '执行时项目资料、方案、问题及渠道非敏感配置快照',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / PARTIAL_FAILED / FAILED',
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_task_request (request_id),
    KEY idx_diagnosis_task_plan_created (plan_id, created_at),
    KEY idx_diagnosis_task_status (status, created_at),
    KEY idx_diagnosis_task_project (project_id),
    CONSTRAINT fk_diagnosis_task_plan FOREIGN KEY (plan_id) REFERENCES geo_diagnosis_plan(id),
    CONSTRAINT chk_diagnosis_task_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断任务：诊断方案的一次执行';

CREATE TABLE IF NOT EXISTS geo_diagnosis_task_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断任务ID',
    question_id BIGINT UNSIGNED NOT NULL COMMENT '来源问题ID，仅作追溯；方案编辑可能重建问题',
    question_sort_order INT NOT NULL COMMENT '执行时问题序号',
    question_snapshot JSON NOT NULL COMMENT '执行时问题正文、来源、意图、品牌范围、语言',
    channel_id BIGINT NOT NULL COMMENT '执行时渠道ID',
    channel_snapshot JSON NOT NULL COMMENT '渠道名称、代码、协议、模型等非敏感配置',
    repeat_index INT NOT NULL COMMENT '本问题本渠道第几次独立采样，从1开始',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED',
    answer_text LONGTEXT NULL COMMENT '模型回答正文；未成功时为空',
    citations JSON NULL COMMENT '接口结构化引用来源；成功无引用时存空数组',
    model_name VARCHAR(200) NULL COMMENT '接口返回的实际模型',
    usage_info JSON NULL COMMENT '接口返回的Token用量，未提供时为空',
    duration_ms BIGINT NULL COMMENT '本次调用耗时毫秒',
    finish_reason VARCHAR(100) NULL COMMENT '接口返回的结束原因',
    raw_response LONGTEXT NULL COMMENT '模型响应体；不得包含请求密钥或认证头',
    error_message VARCHAR(1000) NULL COMMENT '脱敏后的失败原因',
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_task_result_sample (task_id, question_sort_order, channel_id, repeat_index),
    KEY idx_diagnosis_task_result_status (task_id, status),
    CONSTRAINT fk_diagnosis_task_result_task FOREIGN KEY (task_id) REFERENCES geo_diagnosis_task(id),
    CONSTRAINT chk_diagnosis_task_result_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_diagnosis_task_result_repeat CHECK (repeat_index BETWEEN 1 AND 10),
    CONSTRAINT chk_diagnosis_task_result_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断任务中每次独立采样的结果';

-- 诊断报告：每次手动生成一份，重新生成不覆盖旧报告；只为成功且未截断的诊断结果建立分析记录。
CREATE TABLE IF NOT EXISTS geo_diagnosis_task_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断任务ID',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED',
    judge_channel_snapshot JSON NULL COMMENT '判定所用渠道的非敏感配置快照，开始判定时写入',
    prompt_version VARCHAR(50) NULL COMMENT '判定提示词版本',
    summary JSON NULL COMMENT '冻结的汇总指标、主要发现与优化建议，生成成功后写入',
    error_message VARCHAR(1000) NULL COMMENT '脱敏后的生成失败原因',
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_diagnosis_task_report_task (task_id, created_at),
    KEY idx_diagnosis_task_report_status (status),
    CONSTRAINT fk_diagnosis_task_report_task FOREIGN KEY (task_id) REFERENCES geo_diagnosis_task(id),
    CONSTRAINT chk_diagnosis_task_report_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断报告：针对一次诊断任务生成，每次生成一份';

CREATE TABLE IF NOT EXISTS geo_diagnosis_task_result_analysis (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断报告ID（geo_diagnosis_task_report）',
    task_result_id BIGINT UNSIGNED NOT NULL COMMENT '被分析的采样结果ID（geo_diagnosis_task_result）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '判定状态：PENDING / SUCCEEDED / FAILED',
    brand_sentiment VARCHAR(20) NULL COMMENT '点名问题中模型对目标的评价倾向：POSITIVE / NEUTRAL / NEGATIVE，其他问题为空',
    description_accurate TINYINT(1) NULL COMMENT '点名问题中对目标的描述是否准确，其他问题为空',
    brands JSON NULL COMMENT '回答中出现的目标、竞品及待确认产品，含是否推荐、排名、推荐强度',
    points JSON NULL COMMENT '回答中的观点：选择标准、用户顾虑、正负面点、错误信息、推荐理由、保留意见、维度胜负，含原文片段',
    sources JSON NULL COMMENT '来源归类：网址、平台、来源类型及匹配的发布记录',
    error_message VARCHAR(1000) NULL COMMENT '脱敏后的判定失败原因',
    manually_corrected TINYINT(1) NOT NULL DEFAULT 0 COMMENT '判定结果是否经过人工修正',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_task_result_analysis (report_id, task_result_id),
    KEY idx_diagnosis_task_result_analysis_status (report_id, status),
    KEY idx_diagnosis_task_result_analysis_result (task_result_id),
    CONSTRAINT fk_diagnosis_task_result_analysis_report FOREIGN KEY (report_id) REFERENCES geo_diagnosis_task_report(id),
    CONSTRAINT fk_diagnosis_task_result_analysis_result FOREIGN KEY (task_result_id) REFERENCES geo_diagnosis_task_result(id),
    CONSTRAINT chk_diagnosis_task_result_analysis_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_diagnosis_task_result_analysis_sentiment CHECK (brand_sentiment IS NULL OR brand_sentiment IN ('POSITIVE','NEUTRAL','NEGATIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断报告中对每条采样结果的分析';
