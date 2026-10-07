package org.huangry.colorful.geo.domain.model;

import lombok.Builder;
import lombok.Value;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

import java.time.LocalDateTime;

/**
 * 一篇内容在一个平台上的发布记录；保存确认稿快照及平台明确返回的结果。
 */
@Value
@Builder
public class ContentPublishRecord {

    /** 本地发布记录标识。 */
    Long id;
    /** 所属内容优化记录 ID，仅作逻辑关联。 */
    Long contentOptimizationRecordId;
    /** 目标平台。 */
    PublicationPlatformType platformType;
    /** 用户确认的标题快照。 */
    String publicationTitle;
    /** 用户确认的正文快照。 */
    String publicationContent;
    /** 该平台独立的预发布或发布状态。 */
    ContentPublishRecordStatus publishStatus;
    /** 平台明确返回的草稿或文章 ID；未返回时为空。 */
    String remoteContentId;
    /** 草稿编辑链接；未返回时为空。 */
    String draftUrl;
    /** 后续公开发布成功后的访问链接。 */
    String publishedUrl;
    /** 不含凭据和正文的失败或待核对说明。 */
    String failureReason;
    /** 发布记录创建时间。 */
    LocalDateTime createdAt;
    /** 发布记录最近更新时间。 */
    LocalDateTime updatedAt;
}
