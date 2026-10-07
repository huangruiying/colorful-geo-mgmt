package org.huangry.colorful.geo.application.content.model;

import java.util.List;

/**
 * 内容记录的分页结果，供管理页面按页加载，不返回全部正文。
 *
 * @param items 当前页摘要
 * @param total 记录总数
 * @param page 从零开始的页码
 * @param size 每页条数
 */
public record ContentOptimizationRecordPage(List<ContentOptimizationRecordSummary> items, long total, int page, int size) {
}
