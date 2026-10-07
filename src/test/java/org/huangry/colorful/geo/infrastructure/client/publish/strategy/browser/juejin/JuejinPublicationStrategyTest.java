package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.juejin;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.juejin.JuejinBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证掘金策略只在浏览器客户端确认草稿后返回预发布成功。 */
class JuejinPublicationStrategyTest {

	/** 已确认草稿的标识和草稿链接必须原样传给上层。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		JuejinBrowserClient client = mock(JuejinBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new JuejinBrowserClient.DraftCreated("987654",
						"https://juejin.cn/editor/drafts/987654"));
		JuejinPublicationStrategy strategy = new JuejinPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.JUEJIN).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertEquals("987654", result.getRemoteContentId());
		assertEquals("https://juejin.cn/editor/drafts/987654", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
