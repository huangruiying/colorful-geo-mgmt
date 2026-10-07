package org.huangry.colorful.geo.infrastructure.client.publish.model;

import lombok.Builder;
import lombok.Value;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;

/**
 * 单个平台投放策略的标准执行结果。
 *
 * <p>承载官方 API 或浏览器自动化的处理状态，以及所属平台、远程内容标识或访问链接。</p>
 *
 * @author huangry
 */
@Value
@Builder(toBuilder = true)
public class PublicationResult {

    /** 单个平台任务的处理状态，由策略返回；不能直接代表整篇内容的多平台发布状态。 */
    PublicationTaskStatus status;

    /** 本条结果所属平台，批量投放时用于区分各平台的处理结果。 */
    PublicationPlatformType platformType;

    /** 平台返回的文章、草稿或视频等内容标识，可为空；是否发布成功仍以状态为准。 */
    String remoteContentId;

    /** 平台已发布内容的访问链接，尚未发布或平台未返回时可为空。 */
    String publishedUrl;

    /** 平台草稿的编辑链接；创建草稿时返回，不作为公开访问链接使用。 */
    String draftUrl;

    /** 本次处理的简要说明，可为空；不应包含账号凭据或完整请求内容。 */
    String message;
}
