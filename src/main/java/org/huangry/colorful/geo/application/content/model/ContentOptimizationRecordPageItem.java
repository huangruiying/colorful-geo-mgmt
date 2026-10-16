package org.huangry.colorful.geo.application.content.model;


import java.time.LocalDateTime;

/**
 * 「内容投放 → 内容优化记录」页面内容列表的一行，由 GET content-optimize/list 分页返回。
 * <p>
 * 只含标题、创建时间和各平台发布状态计数，不加载正文；查看单条详情使用 {@code ContentOptimizationRecord}。
 *
 * @param id 内容记录标识
 * @param title 标题
 * @param publishRecordCount 已创建发布记录的平台数
 * @param draftCount 已创建草稿的平台数
 * @param processingCount 执行中或待核对的平台数
 * @param failedCount 预发布失败的平台数
 * @param createdAt 创建时间
 */
public record ContentOptimizationRecordPageItem(long id, String title, int publishRecordCount,
                                                int draftCount, int processingCount, int failedCount,
                                                LocalDateTime createdAt) {
}
