-- 修正旧版将“草稿结果未确认”误记为预发布失败的记录，避免被失败重试路径删除。
USE colorful_geo;
SET NAMES utf8mb4;

UPDATE content_publish_record
SET publish_status = 1
WHERE publish_status = 5
  AND failure_reason IS NOT NULL
  AND (failure_reason LIKE '%核对草稿箱%'
       OR failure_reason LIKE '%结果未确认%');
