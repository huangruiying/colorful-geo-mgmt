-- 保留已有随机性设置，允许后续显式清空以不发送 temperature。
ALTER TABLE geo_llm_channel
  MODIFY COLUMN temperature DOUBLE NULL DEFAULT NULL COMMENT '可选生成随机性，空值不发送';
