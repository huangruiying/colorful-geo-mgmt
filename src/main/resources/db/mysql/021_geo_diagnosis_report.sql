-- 诊断报告：每次手动生成保存一份报告及其逐条回答分析，重新生成不覆盖旧报告。
-- 只为成功且未截断的诊断结果建立分析记录；可由快照、引用和原始响应推导的信息不重复保存。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS geo_diagnosis_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exec_record_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断任务ID',
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
    KEY idx_diagnosis_report_task (exec_record_id, created_at),
    CONSTRAINT fk_diagnosis_report_task FOREIGN KEY (exec_record_id) REFERENCES geo_diagnosis_plan_exec_record(id),
    CONSTRAINT chk_diagnosis_report_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断报告，每次生成一份';

CREATE TABLE IF NOT EXISTS geo_diagnosis_answer_analysis (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断报告ID',
    result_id BIGINT UNSIGNED NOT NULL COMMENT '被分析的诊断结果ID',
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
    UNIQUE KEY uk_diagnosis_analysis_result (report_id, result_id),
    KEY idx_diagnosis_analysis_status (report_id, status),
    CONSTRAINT fk_diagnosis_analysis_report FOREIGN KEY (report_id) REFERENCES geo_diagnosis_report(id),
    CONSTRAINT fk_diagnosis_analysis_result FOREIGN KEY (result_id) REFERENCES geo_diagnosis_plan_exec_record_result(id),
    CONSTRAINT chk_diagnosis_analysis_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_diagnosis_analysis_sentiment CHECK (brand_sentiment IS NULL OR brand_sentiment IN ('POSITIVE','NEUTRAL','NEGATIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断报告中逐条有效回答的分析结果';
