package org.huangry.colorful.geo.infrastructure.client.publish.model;

import lombok.Builder;
import lombok.Value;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

/**
 * 已有平台草稿的公开发布请求。
 *
 * <p>由应用层从 prePublish 结果取得远程标识；不携带正文，避免发布阶段意外再建一篇内容。</p>
 *
 * @author huangry
 */
@Value
@Builder
public class PrePublicationRequest {

    /** 已创建草稿的目标平台，必填。 */
    PublicationPlatformType platformType;

    /** 平台返回的草稿标识，必填；仅作为定位目标，不作为幂等保证。 */
    String remoteContentId;
}
