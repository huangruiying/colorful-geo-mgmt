package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xiaohongshu;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.xiaohongshu.XiaohongshuBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 小红书长文草稿的预发布策略。
 *
 * <p>execPrePublish 使用数据库浏览器会话创建小红书长文草稿；公开发布尚未接入，
 * 长文发布按钮待真实联调确认，不负责任务落库或自动重试。</p>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class XiaohongshuPublicationStrategy extends AbstractPublicationStrategy {

	private final XiaohongshuBrowserClient browserClient;

	/**
	 * 返回小红书平台；当前仅支持创建长文草稿。
	 *
	 * @return 小红书平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.XIAOHONGSHU;
	}

	/**
	 * 使用数据库浏览器会话创建并回读小红书长文草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 已确认的草稿结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		XiaohongshuBrowserClient.DraftCreated draft = browserClient.createDraft(
				request.getTitle(), request.getContent());
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draft.remoteContentId())
				.draftUrl(draft.draftUrl())
				.message("小红书长文草稿已创建，尚未公开发布，请到小红书草稿箱核对")
				.build();
	}

	/**
	 * 小红书长文公开发布暂未接入，禁止把草稿误标成已发布。
	 *
	 * <p>长文编辑页的「发布」触发方式在真实联调中尚未确认（可能与长文合集／自动保存相关），
	 * 待确认后再实现 execPublish，避免伪造发布成功。</p>
	 *
	 * @param request 已有平台草稿
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("小红书草稿公开发布暂未接入，当前仅支持创建长文草稿");
	}
}
