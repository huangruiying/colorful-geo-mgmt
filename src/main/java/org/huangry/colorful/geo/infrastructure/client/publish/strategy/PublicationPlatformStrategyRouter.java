package org.huangry.colorful.geo.infrastructure.client.publish.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按平台选择唯一策略，再执行指定的动作。
 *
 * <p>prePublish 创建草稿，publish 发布已有草稿；不负责审核、任务持久化或自动重试。</p>
 *
 * @author huangry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicationPlatformStrategyRouter {

    private final List<PublicationPlatformStrategy> strategies;

    /**
     * 为支持草稿的平台创建草稿。
     *
     * @param request 已审核的单平台内容请求
     * @return 草稿创建结果
     */
    public PublicationResult prePublish(PlatformPublicationRequest request) {
        // 校验完整内容后，再按平台选择唯一草稿策略。
        validateContentRequest(request);
        PublicationPlatformStrategy strategy = selectStrategy(request.getPlatformType());
        log.info("选择草稿创建策略，platformType={}，strategy={}",
                request.getPlatformType(), strategy.getClass().getSimpleName());
        return strategy.prePublish(request).toBuilder().platformType(request.getPlatformType()).build();
    }

    /**
     * 将已有草稿公开发布，不重新提交标题和正文。
     *
     * @param request 已有草稿请求
     * @return 平台发布结果
     */
    public PublicationResult publish(PrePublicationRequest request) {
        // 已有草稿不接受空标识，避免策略在平台端选择错误内容。
        if (request == null || request.getPlatformType() == null
                || request.getRemoteContentId() == null || request.getRemoteContentId().isBlank()) {
            throw new PublicationClientException("草稿平台和远程草稿标识不能为空");
        }
        PublicationPlatformStrategy strategy = selectStrategy(request.getPlatformType());
        log.info("选择草稿发布策略，platformType={}，strategy={}",
                request.getPlatformType(), strategy.getClass().getSimpleName());
        return strategy.publish(request).toBuilder().platformType(request.getPlatformType()).build();
    }

    /**
     * 校验内容输入，避免请求不完整时进入平台路由。
     * @param request 单平台内容请求
     */
    private void validateContentRequest(PlatformPublicationRequest request) {
        if (request == null || request.getPlatformType() == null
                || request.getTitle() == null || request.getTitle().isBlank()
                || request.getContent() == null || request.getContent().isBlank()) {
            throw new PublicationClientException("平台、标题和正文不能为空");
        }
    }

    /**
     * 同一平台只能命中一个策略，避免发布动作被执行两次。
     * @param platformType 目标平台
     * @return 唯一命中的策略
     */
    private PublicationPlatformStrategy selectStrategy(PublicationPlatformType platformType) {
        List<PublicationPlatformStrategy> matched = strategies.stream()
                .filter(strategy -> strategy.platformType() == platformType).toList();
        if (matched.isEmpty()) {
            throw new PublicationClientException("未接入目标平台的投放策略: " + platformType);
        }
        if (matched.size() > 1) {
            throw new PublicationClientException("目标平台匹配到多个投放策略: " + platformType);
        }
        return matched.get(0);
    }
}
