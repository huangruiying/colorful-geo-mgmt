-- 诊断任务相关四张表按“任务 → 采样结果 → 报告 → 逐条分析”的层级统一改名，保留已有数据；
-- 已执行 022 的库执行一次，新库使用 001_init.sql，无需执行本脚本。
--   geo_diagnosis_plan_exec_record        → geo_diagnosis_task                    （一次诊断任务）
--   geo_diagnosis_plan_exec_record_result → geo_diagnosis_task_result             （任务内每次独立采样的结果）
--   geo_diagnosis_result_output           → geo_diagnosis_task_report             （针对一次任务生成的报告）
--   geo_diagnosis_result_analysis         → geo_diagnosis_task_result_analysis    （报告中对每条采样结果的分析）
-- 关联列：exec_record_id → task_id；分析表 output_id → report_id、result_id → task_result_id。
-- 索引与约束名称同步，便于从名称对应到表。执行前需停止读写这些表的服务实例，执行后用新代码重启。
SET NAMES utf8mb4;

-- 1. 先删除所有外键和检查约束：外键引用旧列、约束名带旧表名，需删后重建
ALTER TABLE geo_diagnosis_result_analysis
    DROP FOREIGN KEY fk_diagnosis_result_analysis_output,
    DROP FOREIGN KEY fk_diagnosis_result_analysis_result,
    DROP CHECK chk_diagnosis_result_analysis_status,
    DROP CHECK chk_diagnosis_result_analysis_sentiment;
ALTER TABLE geo_diagnosis_result_output
    DROP FOREIGN KEY fk_diagnosis_result_output_task,
    DROP CHECK chk_diagnosis_result_output_status;
ALTER TABLE geo_diagnosis_plan_exec_record_result
    DROP FOREIGN KEY fk_diagnosis_exec_result_record,
    DROP CHECK chk_diagnosis_exec_result_status,
    DROP CHECK chk_diagnosis_exec_repeat,
    DROP CHECK chk_diagnosis_exec_duration;
ALTER TABLE geo_diagnosis_plan_exec_record
    DROP FOREIGN KEY fk_diagnosis_exec_plan,
    DROP CHECK chk_diagnosis_exec_status;

-- 2. 四张表改名
RENAME TABLE geo_diagnosis_plan_exec_record TO geo_diagnosis_task,
             geo_diagnosis_plan_exec_record_result TO geo_diagnosis_task_result,
             geo_diagnosis_result_output TO geo_diagnosis_task_report,
             geo_diagnosis_result_analysis TO geo_diagnosis_task_result_analysis;

-- 3. 诊断任务：索引改名并重建约束
ALTER TABLE geo_diagnosis_task
    RENAME INDEX uk_diagnosis_exec_request TO uk_diagnosis_task_request,
    RENAME INDEX idx_diagnosis_exec_plan_created TO idx_diagnosis_task_plan_created,
    RENAME INDEX idx_diagnosis_exec_status TO idx_diagnosis_task_status;
ALTER TABLE geo_diagnosis_task
    ADD CONSTRAINT fk_diagnosis_task_plan FOREIGN KEY (plan_id) REFERENCES geo_diagnosis_plan(id),
    ADD CONSTRAINT chk_diagnosis_task_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED')),
    COMMENT='诊断任务：诊断方案的一次执行';

-- 4. 采样结果：关联列与索引改名，重建约束
ALTER TABLE geo_diagnosis_task_result
    RENAME COLUMN exec_record_id TO task_id,
    RENAME INDEX uk_diagnosis_exec_sample TO uk_diagnosis_task_result_sample,
    RENAME INDEX idx_diagnosis_exec_result_status TO idx_diagnosis_task_result_status;
ALTER TABLE geo_diagnosis_task_result
    MODIFY task_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断任务ID',
    ADD CONSTRAINT fk_diagnosis_task_result_task FOREIGN KEY (task_id) REFERENCES geo_diagnosis_task(id),
    ADD CONSTRAINT chk_diagnosis_task_result_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_diagnosis_task_result_repeat CHECK (repeat_index BETWEEN 1 AND 10),
    ADD CONSTRAINT chk_diagnosis_task_result_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    COMMENT='诊断任务中每次独立采样的结果';

-- 5. 报告：关联列与索引改名，重建约束
ALTER TABLE geo_diagnosis_task_report
    RENAME COLUMN exec_record_id TO task_id,
    RENAME INDEX idx_diagnosis_result_output_task TO idx_diagnosis_task_report_task;
ALTER TABLE geo_diagnosis_task_report
    MODIFY task_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断任务ID',
    ADD CONSTRAINT fk_diagnosis_task_report_task FOREIGN KEY (task_id) REFERENCES geo_diagnosis_task(id),
    ADD CONSTRAINT chk_diagnosis_task_report_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    COMMENT='诊断报告：针对一次诊断任务生成，每次生成一份';

-- 6. 逐条分析：两个关联列与索引改名，重建约束
ALTER TABLE geo_diagnosis_task_result_analysis
    RENAME COLUMN output_id TO report_id,
    RENAME COLUMN result_id TO task_result_id,
    RENAME INDEX uk_diagnosis_result_analysis TO uk_diagnosis_task_result_analysis,
    RENAME INDEX idx_diagnosis_result_analysis_status TO idx_diagnosis_task_result_analysis_status,
    RENAME INDEX idx_diagnosis_result_analysis_result TO idx_diagnosis_task_result_analysis_result;
ALTER TABLE geo_diagnosis_task_result_analysis
    MODIFY report_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断报告ID（geo_diagnosis_task_report）',
    MODIFY task_result_id BIGINT UNSIGNED NOT NULL COMMENT '被分析的采样结果ID（geo_diagnosis_task_result）',
    ADD CONSTRAINT fk_diagnosis_task_result_analysis_report FOREIGN KEY (report_id) REFERENCES geo_diagnosis_task_report(id),
    ADD CONSTRAINT fk_diagnosis_task_result_analysis_result FOREIGN KEY (task_result_id) REFERENCES geo_diagnosis_task_result(id),
    ADD CONSTRAINT chk_diagnosis_task_result_analysis_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_diagnosis_task_result_analysis_sentiment CHECK (brand_sentiment IS NULL OR brand_sentiment IN ('POSITIVE','NEUTRAL','NEGATIVE')),
    COMMENT='诊断报告中对每条采样结果的分析';
