package org.huangry.colorful.geo.application.content.model;

import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;

import java.util.List;

/**
 * 一篇优化内容及其逐平台发布记录；页面据此查看原文、优化稿和草稿结果。
 *
 * @param record 优化输入与输出
 * @param publishRecords 各平台独立发布记录
 */
public record ContentOptimizationRecordDetail(ContentOptimizationRecord record, List<ContentPublishRecord> publishRecords) {
}
