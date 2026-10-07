package org.huangry.colorful.geo.infrastructure.client.publish.strategy;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;

/**
 * 平台投放策略的公共执行模板。
 *
 * <p>对外固定 prePublish 创建草稿、publish 发布已有草稿的校验顺序；
 * 平台差异由子类的 execPrePublish、execPublish 实现，不负责审核与任务持久化。</p>
 *
 * @author huangry
 */
public abstract class AbstractPublicationStrategy implements PublicationPlatformStrategy {

    /**
     * 校验待创建草稿的内容，再执行平台创建动作。
     * @param request 单平台内容请求
     * @return 草稿创建结果
     */
    @Override
    public final PublicationResult prePublish(PlatformPublicationRequest request) {
        // 1. 在调用平台前校验草稿内容与目标。
        validatePrePublishRequest(request);
        // 2. 平台策略只负责具体草稿创建。
        return execPrePublish(request);
    }

    /**
     * 校验已有草稿标识，再执行平台公开发布动作。
     * @param request 已有草稿请求
     * @return 公开发布结果
     */
    @Override
    public final PublicationResult publish(PrePublicationRequest request) {
        // 1. 确认草稿目标完整，避免发布到错误平台。
        validatePublishRequest(request);
        // 2. 平台策略只负责具体公开发布。
        return execPublish(request);
    }

    /**
     * 校验草稿创建所需的标题、正文和策略所属平台。
     *
     * @param request 单平台内容请求
     */
    protected void validatePrePublishRequest(PlatformPublicationRequest request) {
        if (request == null) {
            throw new PublicationClientException("平台投放请求不能为空");
        }
        if (request.getPlatformType() == null) {
            throw new PublicationClientException("平台类型不能为空");
        }
        if (platformType() != request.getPlatformType()) {
            throw new PublicationClientException(platformType().getDisplayName()
                    + "策略仅支持 " + platformType() + " 平台");
        }
        if (isBlank(request.getTitle()) || isBlank(request.getContent())) {
            throw new PublicationClientException("平台投放标题和正文不能为空");
        }
    }

    /**
     * 校验已有草稿发布所需的策略所属平台和远程标识。
     * @param request 已有草稿请求
     */
    protected void validatePublishRequest(PrePublicationRequest request) {
        if (request == null || request.getPlatformType() == null
                || isBlank(request.getRemoteContentId())) {
            throw new PublicationClientException("草稿平台和远程草稿标识不能为空");
        }
        if (platformType() != request.getPlatformType()) {
            throw new PublicationClientException(platformType().getDisplayName()
                    + "草稿发布策略仅支持 " + platformType() + " 平台");
        }
    }

    /**
     * 执行平台特定的草稿创建。
     * @param request 已校验的内容请求
     * @return 草稿创建结果
     */
    protected abstract PublicationResult execPrePublish(PlatformPublicationRequest request);

    /**
     * 执行平台特定的已有草稿发布。
     * @param request 已校验的草稿请求
     * @return 公开发布结果
     */
    protected abstract PublicationResult execPublish(PrePublicationRequest request);

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
