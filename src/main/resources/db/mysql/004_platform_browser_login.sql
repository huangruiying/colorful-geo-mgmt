-- Playwright 独立登录态；与 Wechatsync 和内容发布记录无关联。
USE colorful_geo;

CREATE TABLE IF NOT EXISTS platform_browser_login (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '登录记录 ID',
    platform_type VARCHAR(64) NOT NULL COMMENT '平台枚举名称，首版每个平台一个账号',
    account_name VARCHAR(255) NULL COMMENT '平台核验返回的账号名',
    login_status VARCHAR(32) NOT NULL COMMENT 'LOGGED_IN、EXPIRED、UNKNOWN',
    storage_state LONGTEXT NOT NULL COMMENT 'Playwright storageState JSON，仅服务端读取',
    last_login_at DATETIME(3) NOT NULL COMMENT '最近一次登录时间',
    last_checked_at DATETIME(3) NULL COMMENT '最近一次主动核验时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_browser_login_platform (platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立 Playwright 平台登录态';
