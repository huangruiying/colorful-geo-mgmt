-- 仅用于已执行旧版 004 脚本的数据库；全新数据库直接执行最新版 004。
-- 原密文保留在重命名后的字段中，但不可作为 Playwright JSON 使用；旧账号需重新扫码。
USE colorful_geo;

ALTER TABLE platform_browser_login
    CHANGE COLUMN encrypted_storage_state storage_state LONGTEXT NOT NULL
    COMMENT 'Playwright storageState JSON；迁移前的密文需重新扫码覆盖';

UPDATE platform_browser_login SET login_status = 'EXPIRED';
