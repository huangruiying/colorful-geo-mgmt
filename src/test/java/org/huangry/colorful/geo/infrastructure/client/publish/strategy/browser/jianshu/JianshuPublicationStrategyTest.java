package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.jianshu;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.jianshu.JianshuBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证简书策略只在浏览器客户端确认草稿后返回预发布成功。 */
class JianshuPublicationStrategyTest {

	/** 已确认草稿的标识和写作页链接必须原样传给上层。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		JianshuBrowserClient client = mock(JianshuBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new JianshuBrowserClient.DraftCreated("144599844",
						"https://www.jianshu.com/writer#/notebooks/1/notes/144599844"));
		JianshuPublicationStrategy strategy = new JianshuPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.JIANSHU).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertEquals("144599844", result.getRemoteContentId());
		assertEquals("https://www.jianshu.com/writer#/notebooks/1/notes/144599844", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
