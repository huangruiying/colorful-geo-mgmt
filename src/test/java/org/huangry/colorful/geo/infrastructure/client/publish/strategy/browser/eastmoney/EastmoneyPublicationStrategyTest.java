package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.eastmoney;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.eastmoney.EastmoneyBrowserClient;
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

/** 验证东方财富策略只在浏览器客户端确认草稿后返回预发布成功。 */
class EastmoneyPublicationStrategyTest {

	/** 草稿 id 可能为空，远程标识为空仍可返回草稿状态。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		EastmoneyBrowserClient client = mock(EastmoneyBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new EastmoneyBrowserClient.DraftCreated(null,
						"https://author.eastmoney.com/editor"));
		EastmoneyPublicationStrategy strategy = new EastmoneyPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.EASTMONEY).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertNull(result.getRemoteContentId());
		assertEquals("https://author.eastmoney.com/editor", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
