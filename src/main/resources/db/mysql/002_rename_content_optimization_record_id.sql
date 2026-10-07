-- 已有库执行一次：按现有约束名移除外键，再将关联字段改为完整名称；数据与唯一索引保留。
USE colorful_geo;

SET @publish_record_foreign_key = (
    SELECT CONSTRAINT_NAME
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'content_publish_record'
      AND COLUMN_NAME = 'content_record_id'
      AND REFERENCED_TABLE_NAME = 'content_optimization_record'
    LIMIT 1
);
SET @drop_publish_record_foreign_key_sql = IF(
    @publish_record_foreign_key IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE content_publish_record DROP FOREIGN KEY `',
           REPLACE(@publish_record_foreign_key, '`', '``'), '`')
);
PREPARE drop_publish_record_foreign_key FROM @drop_publish_record_foreign_key_sql;
EXECUTE drop_publish_record_foreign_key;
DEALLOCATE PREPARE drop_publish_record_foreign_key;

ALTER TABLE content_publish_record
    RENAME COLUMN content_record_id TO content_optimization_record_id;
