-- 诊断报告两张表改名，保留已有数据；已执行 021 的库执行一次，新库使用 001_init.sql，无需执行本脚本。
-- geo_diagnosis_report → geo_diagnosis_result_output；geo_diagnosis_answer_analysis → geo_diagnosis_result_analysis，
-- 分析表的 report_id 列改为 output_id；索引与约束名称同步，便于从名称对应到表。
SET NAMES utf8mb4;

RENAME TABLE geo_diagnosis_report TO geo_diagnosis_result_output,
             geo_diagnosis_answer_analysis TO geo_diagnosis_result_analysis;

-- 1. 报告表：外键与检查约束需先删后建才能改名，索引可直接改名
ALTER TABLE geo_diagnosis_result_output
    DROP FOREIGN KEY fk_diagnosis_report_task,
    DROP CHECK chk_diagnosis_report_status;
ALTER TABLE geo_diagnosis_result_output
    RENAME INDEX idx_diagnosis_report_task TO idx_diagnosis_result_output_task,
    ADD CONSTRAINT fk_diagnosis_result_output_task FOREIGN KEY (exec_record_id) REFERENCES geo_diagnosis_plan_exec_record(id),
    ADD CONSTRAINT chk_diagnosis_result_output_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    COMMENT='诊断报告，每次生成一份';

-- 2. 分析表：先删除引用旧列和旧名称的约束，再改列名、索引名并重建约束
ALTER TABLE geo_diagnosis_result_analysis
    DROP FOREIGN KEY fk_diagnosis_analysis_report,
    DROP FOREIGN KEY fk_diagnosis_analysis_result,
    DROP CHECK chk_diagnosis_analysis_status,
    DROP CHECK chk_diagnosis_analysis_sentiment;
ALTER TABLE geo_diagnosis_result_analysis
    RENAME COLUMN report_id TO output_id,
    RENAME INDEX uk_diagnosis_analysis_result TO uk_diagnosis_result_analysis,
    RENAME INDEX idx_diagnosis_analysis_status TO idx_diagnosis_result_analysis_status,
    RENAME INDEX fk_diagnosis_analysis_result TO idx_diagnosis_result_analysis_result;
ALTER TABLE geo_diagnosis_result_analysis
    MODIFY output_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断报告ID（geo_diagnosis_result_output）',
    ADD CONSTRAINT fk_diagnosis_result_analysis_output FOREIGN KEY (output_id) REFERENCES geo_diagnosis_result_output(id),
    ADD CONSTRAINT fk_diagnosis_result_analysis_result FOREIGN KEY (result_id) REFERENCES geo_diagnosis_plan_exec_record_result(id),
    ADD CONSTRAINT chk_diagnosis_result_analysis_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_diagnosis_result_analysis_sentiment CHECK (brand_sentiment IS NULL OR brand_sentiment IN ('POSITIVE','NEUTRAL','NEGATIVE'));
