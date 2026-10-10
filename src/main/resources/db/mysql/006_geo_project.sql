-- 增量添加项目基础档案；不会重建或修改已有业务表。
CREATE TABLE IF NOT EXISTS geo_project (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '项目ID，供任务关联',
    name VARCHAR(200) NOT NULL COMMENT '项目或产品名称',
    website_url VARCHAR(2048) NOT NULL COMMENT '官网或资料URL',
    industry VARCHAR(200) NOT NULL COMMENT '所属行业或品类',
    core_features TEXT NOT NULL COMMENT '核心功能',
    target_audience TEXT NOT NULL COMMENT '目标用户群体',
    aliases JSON NOT NULL COMMENT '别名或品牌名列表',
    competitors JSON NOT NULL COMMENT '主要竞品列表',
    target_market VARCHAR(200) NULL COMMENT '目标市场',
    target_language VARCHAR(100) NULL COMMENT '目标语言',
    selling_points TEXT NULL COMMENT '核心卖点',
    pricing TEXT NULL COMMENT '价格说明',
    keywords JSON NOT NULL COMMENT '目标关键词列表',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目基础档案';
