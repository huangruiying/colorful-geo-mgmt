package org.huangry.colorful.geo.application.content.model;

import org.huangry.colorful.geo.domain.model.ContentPublishRecord;

import java.util.List;

/**
 * 发布记录分页结果；列表读取本地数据，不查询或修改平台内容。
 *
 * @param items 当前页发布记录
 * @param total 记录总数
 * @param page 从零开始的页码
 * @param size 每页条数
 */
public record ContentPublishRecordPage(List<ContentPublishRecord> items, long total, int page, int size) {
}
