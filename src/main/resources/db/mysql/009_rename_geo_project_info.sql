-- 已有 geo_project 表执行一次，保留原项目数据及索引；新库无需执行。
RENAME TABLE geo_project TO geo_project_info;
