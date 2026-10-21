package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xiaohongshu;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.xiaohongshu.XiaohongshuBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证小红书策略只在浏览器客户端确认草稿后返回预发布成功。 */
class XiaohongshuPublicationStrategyTest {

	/** 长文草稿 id 可能为空，远程标识为空仍可返回草稿状态。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		XiaohongshuBrowserClient client = mock(XiaohongshuBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new XiaohongshuBrowserClient.DraftCreated(null,
						"https://creator.xiaohongshu.com/creator/editor"));
		XiaohongshuPublicationStrategy strategy = new XiaohongshuPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.XIAOHONGSHU).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertNull(result.getRemoteContentId());
		assertEquals("https://creator.xiaohongshu.com/creator/editor", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
