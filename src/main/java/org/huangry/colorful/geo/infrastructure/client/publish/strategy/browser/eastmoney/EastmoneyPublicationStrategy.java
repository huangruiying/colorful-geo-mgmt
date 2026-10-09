package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.eastmoney;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.eastmoney.EastmoneyBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 东方财富草稿的预发布策略（已接入并真实验证）。
 *
 * <p>execPrePublish 委托 {@link EastmoneyBrowserClient} 使用数据库浏览器会话创建东方财富草稿；
 * 创作者中心入口与编辑器选择器已通过真实已登录会话探索获得，并在真实 macOS 浏览器中完整跑通
 * （填标题+正文→保存并预览→草稿已保存→重载可恢复），非凭印象推断。公开发布尚未接入，
 * execPublish 抛"暂未接入"，禁止把草稿误标成已发布。</p>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class EastmoneyPublicationStrategy extends AbstractPublicationStrategy {

	private final EastmoneyBrowserClient browserClient;

	/**
	 * 返回东方财富平台。
	 *
	 * @return 东方财富平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.EASTMONEY;
	}

	/**
	 * 使用数据库浏览器会话创建东方财富草稿（当前受客户端选择器守卫保护）。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 草稿结果（成功或明确异常）
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		EastmoneyBrowserClient.DraftCreated draft = browserClient.createDraft(
				request.getTitle(), request.getContent());
		String idTip = draft.remoteContentId() != null
				? "平台草稿ID=" + draft.remoteContentId()
				: "未取到平台草稿ID，请到东方财富草稿箱核对";
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draft.remoteContentId())
				.draftUrl(draft.draftUrl())
				.message("东方财富草稿已创建，尚未公开发布；" + idTip)
				.build();
	}

	/**
	 * 东方财富草稿公开发布暂未接入，禁止把草稿误标成已发布。
	 *
	 * <p>东方财富草稿建联调已在真实 macOS 浏览器跑通（见 EastmoneyBrowserClient），但编辑器"发布"按钮
	 * 触发方式尚未经真实联调确认，发布链路待确认后再实现，避免伪造发布成功。</p>
	 *
	 * @param request 已有平台草稿
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("东方财富草稿公开发布暂未接入，当前仅支持创建草稿");
	}
}
