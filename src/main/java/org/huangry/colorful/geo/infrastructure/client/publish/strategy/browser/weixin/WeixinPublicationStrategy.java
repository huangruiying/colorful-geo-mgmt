package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weixin;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.weixin.WeixinBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 微信公众号内容草稿的预发布策略。
 *
 * <p>execPrePublish 使用数据库浏览器会话创建微信公众号草稿；公开发布尚未接入，
 * 不负责任务落库或自动重试。</p>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class WeixinPublicationStrategy extends AbstractPublicationStrategy {

	private final WeixinBrowserClient browserClient;

	/**
	 * 返回微信公众号平台；当前仅支持创建草稿。
	 *
	 * @return 微信公众号平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT;
	}

	/**
	 * 使用数据库中的公众号会话创建并回读文章草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 已确认的草稿结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		String draftId = browserClient.createDraft(request.getTitle(), request.getContent());
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draftId)
				.message("微信公众号草稿已创建，尚未公开发布")
				.build();
	}

	/**
	 * 公众号公开发布尚未完成平台终态核验，不把草稿误标为已发表。
	 *
	 * @param request 已有草稿请求
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("微信公众号草稿公开发布暂未接入");
	}
}
