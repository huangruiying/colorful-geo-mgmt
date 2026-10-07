package org.huangry.colorful.geo.application.content.model;


import java.time.LocalDateTime;

/**
 * 优化内容列表的一行摘要；只返回展示所需字段和逐平台发布数量，不加载正文。
 *
 * @param id 内容记录标识
 * @param title 标题
 * @param publishRecordCount 已创建发布记录的平台数
 * @param draftCount 已创建草稿的平台数
 * @param processingCount 执行中或待核对的平台数
 * @param failedCount 预发布失败的平台数
 * @param createdAt 创建时间
 */
public record ContentOptimizationRecordSummary(long id, String title, int publishRecordCount,
                                   int draftCount, int processingCount, int failedCount,
                                   LocalDateTime createdAt) {
}
