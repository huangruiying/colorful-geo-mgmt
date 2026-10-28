-- 补充诊断报告状态和诊断任务项目两条查询索引；已执行 023 的库执行一次，新库使用 001_init.sql，无需执行本脚本。
-- 报告后台按状态加锁领取、项目删除按项目加锁查任务，缺索引时会扫全表并锁住全部行，阻塞其他报告和任务的执行。
-- 在线建索引，执行期间不阻塞读写，无需停止服务。
-- 执行方式（在 docker-compose.yaml 所在目录，本文件放在同一目录）：
--   docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ugeo_app -Dcolorful_geo' < 024_diagnosis_query_indexes.sql
-- 执行后确认：SHOW INDEX FROM geo_diagnosis_task_report; SHOW INDEX FROM geo_diagnosis_task;
SET NAMES utf8mb4;

-- 1. 报告后台领取待执行、执行中报告及报告列表按状态筛选
ALTER TABLE geo_diagnosis_task_report
    ADD KEY idx_diagnosis_task_report_status (status),
    ALGORITHM=INPLACE, LOCK=NONE;

-- 2. 项目删除和导出按执行时关联项目查任务，与 plan_id 索引合并使用
ALTER TABLE geo_diagnosis_task
    ADD KEY idx_diagnosis_task_project (project_id),
    ALGORITHM=INPLACE, LOCK=NONE;
