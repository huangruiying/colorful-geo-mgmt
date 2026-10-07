package org.huangry.colorful.geo.infrastructure.client.publish.strategy;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

/**
 * 单个平台内容投放策略。
 *
 * <p>策略负责平台差异适配和投放结果转换；审核与任务持久化由上层业务负责。</p>
 *
 * @author huangry
 */
public interface PublicationPlatformStrategy {

    /**
     * 返回该策略负责的平台；具体动作由调用的方法决定。
     *
     * @return 该策略负责的平台
     */
    PublicationPlatformType platformType();

    /**
     * 将内容预先保存为平台草稿。
     *
     * @param request 已审核的单平台投放请求
     * @return 标准投放结果
     */
    PublicationResult prePublish(PlatformPublicationRequest request);

    /**
     * 将已有平台草稿公开发布。
     * @param request 平台和远程草稿标识
     * @return 标准投放结果
     */
    PublicationResult publish(PrePublicationRequest request);
}
