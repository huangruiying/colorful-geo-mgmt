-- 已存在 011 执行表的数据库执行本增量一次；新库使用最新版 001 或 011，无需重复执行。
-- 不回填历史请求标识，保留已有执行记录和快照；新任务由创建接口提供唯一标识。
SET NAMES utf8mb4;

ALTER TABLE geo_diagnosis_plan_exec_record
    ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '创建请求唯一标识，用于防止重复提交；历史记录可为空' AFTER id,
    ADD UNIQUE KEY uk_diagnosis_exec_request (request_id),
    MODIFY COLUMN plan_snapshot JSON NOT NULL
        COMMENT '执行时项目资料、方案、问题及渠道非敏感配置快照';
