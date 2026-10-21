-- 诊断方案只保存配置；后续执行任务另行保存采样快照。
SET NAMES utf8mb4;

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
