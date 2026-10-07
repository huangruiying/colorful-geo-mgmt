package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.eastmoney;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.AbstractWechatsyncPrePublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncClient;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Component;

/**
 * 东方财富内容草稿的预发布策略。
 *
 * <p>execPrePublish 通过 Wechatsync 创建东方财富草稿；公开发布尚未接入，
 * 不负责任务落库或自动重试。</p>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class EastmoneyPublicationStrategy extends AbstractWechatsyncPrePublicationStrategy {

	private final WechatsyncClient wechatsyncClient;

	/**
	 * 返回东方财富平台；当前仅支持创建草稿。
	 *
	 * @return 东方财富平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.EASTMONEY;
	}

	/**
	 * 将已审核 Markdown 正文交给 Wechatsync 创建东方财富草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 已确认的草稿结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		return createDraft(wechatsyncClient, request, "eastmoney");
	}
}
