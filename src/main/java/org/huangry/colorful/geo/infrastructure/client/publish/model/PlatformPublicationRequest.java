package org.huangry.colorful.geo.infrastructure.client.publish.model;

import lombok.Builder;
import lombok.Value;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

/**
 * 批量服务拆分后交给单个平台策略的请求。
 *
 * <p>由应用层传入审核后的最终发布稿；该对象不携带平台账号凭据，凭据只能由对应基础设施客户端读取。</p>
 *
 * @author huangry
 */
@Value
@Builder
public class PlatformPublicationRequest {

    /** 目标平台，必填；由路由器选择唯一平台策略。 */
    PublicationPlatformType platformType;

    /** 已审核的发布标题，统一模板要求非空；平台额外长度限制由具体策略处理。 */
    String title;

    /** 已审核的最终正文，必填；平台格式转换由具体策略处理。 */
    String content;

}
