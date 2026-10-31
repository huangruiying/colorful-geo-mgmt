package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.imooc;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.imooc.ImoocBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 慕课网手记的预发布策略：把内容保存为手记草稿。
 *
 * <p>execPrePublish 使用数据库浏览器会话创建手记草稿；公开发布尚未接入，不负责任务落库或自动重试。</p>
 */
@Component
public class ImoocPublicationStrategy extends AbstractPublicationStrategy {

    @Resource
    private ImoocBrowserClient browserClient;

    /**
     * 返回慕课网平台；当前仅支持创建手记草稿。
     *
     * @return 慕课网平台
     */
    @Override
    public PublicationPlatformType platformType() {
        return PublicationPlatformType.IMOOC;
    }

    /**
     * 使用数据库浏览器会话创建并回读手记草稿。
     *
     * @param request 目标平台、标题和正文
     * @return 已确认的草稿结果
     */
    @Override
    protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
        ImoocBrowserClient.DraftCreated draft = browserClient.createDraft(request.getTitle(), request.getContent());
        return PublicationResult.builder()
                .status(PublicationTaskStatus.DRAFT_CREATED)
                .remoteContentId(draft.remoteContentId())
                .draftUrl(draft.draftUrl())
                .message("慕课网草稿已创建，尚未公开发布")
                .build();
    }

    /**
     * 慕课网手记公开发布尚未核验，禁止把草稿误标成已发布。
     *
     * @param request 已有平台草稿
     * @return 不返回结果，始终抛出未接入异常
     */
    @Override
    protected PublicationResult execPublish(PrePublicationRequest request) {
        throw new PublicationClientException("慕课网草稿公开发布暂未接入");
    }
}
