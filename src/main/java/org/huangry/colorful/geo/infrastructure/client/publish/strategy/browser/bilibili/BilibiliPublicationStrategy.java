package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.bilibili;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.bilibili.BilibiliBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 哔哩哔哩专栏的预发布策略：把内容保存为专栏草稿。
 *
 * <p>execPrePublish 使用数据库浏览器会话创建专栏草稿；公开发布尚未接入，不负责任务落库或自动重试。</p>
 */
@Component
public class BilibiliPublicationStrategy extends AbstractPublicationStrategy {

    @Resource
    private BilibiliBrowserClient browserClient;

    /**
     * 返回哔哩哔哩平台；当前仅支持创建专栏草稿。
     *
     * @return 哔哩哔哩平台
     */
    @Override
    public PublicationPlatformType platformType() {
        return PublicationPlatformType.BILIBILI;
    }

    /**
     * 使用数据库浏览器会话创建并回读专栏草稿。
     *
     * @param request 目标平台、标题和正文
     * @return 已确认的草稿结果
     */
    @Override
    protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
        BilibiliBrowserClient.DraftCreated draft = browserClient.createDraft(request.getTitle(), request.getContent());
        return PublicationResult.builder()
                .status(PublicationTaskStatus.DRAFT_CREATED)
                .remoteContentId(draft.remoteContentId())
                .draftUrl(draft.draftUrl())
                .message("哔哩哔哩专栏草稿已创建，尚未公开发布")
                .build();
    }

    /**
     * 哔哩哔哩专栏公开发布尚未核验，禁止把草稿误标成已发布。
     *
     * @param request 已有平台草稿
     * @return 不返回结果，始终抛出未接入异常
     */
    @Override
    protected PublicationResult execPublish(PrePublicationRequest request) {
        throw new PublicationClientException("哔哩哔哩专栏草稿公开发布暂未接入");
    }
}
