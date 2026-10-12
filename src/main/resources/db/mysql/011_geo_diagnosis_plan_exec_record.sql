-- 方案每次执行独立保存；快照仅包含非敏感配置，不保存 API Key、Cookie 或令牌。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS geo_diagnosis_plan_exec_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id BIGINT UNSIGNED NOT NULL COMMENT '来源方案ID',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '执行时关联项目ID',
    plan_snapshot JSON NOT NULL COMMENT '执行时方案、问题及渠道非敏感配置快照',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / PARTIAL_FAILED / FAILED',
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_diagnosis_exec_plan_created (plan_id, created_at),
    KEY idx_diagnosis_exec_status (status, created_at),
    CONSTRAINT fk_diagnosis_exec_plan FOREIGN KEY (plan_id) REFERENCES geo_diagnosis_plan(id),
    CONSTRAINT chk_diagnosis_exec_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断方案一次执行记录';

CREATE TABLE IF NOT EXISTS geo_diagnosis_plan_exec_record_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exec_record_id BIGINT UNSIGNED NOT NULL COMMENT '所属执行记录ID',
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
    UNIQUE KEY uk_diagnosis_exec_sample (exec_record_id, question_sort_order, channel_id, repeat_index),
    KEY idx_diagnosis_exec_result_status (exec_record_id, status),
    CONSTRAINT fk_diagnosis_exec_result_record FOREIGN KEY (exec_record_id) REFERENCES geo_diagnosis_plan_exec_record(id),
    CONSTRAINT chk_diagnosis_exec_result_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_diagnosis_exec_repeat CHECK (repeat_index BETWEEN 1 AND 10),
    CONSTRAINT chk_diagnosis_exec_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断方案一次执行的逐次回答结果';

