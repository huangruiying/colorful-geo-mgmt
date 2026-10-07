package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weibo;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.weibo.WeiboBrowserClient;
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

/** 验证微博策略只在浏览器客户端确认草稿后返回预发布成功。 */
class WeiboPublicationStrategyTest {

	/** 头条文章编辑器为 hash 路由，草稿 URL 可能不含 id，远程标识为空仍可返回草稿状态。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		WeiboBrowserClient client = mock(WeiboBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new WeiboBrowserClient.DraftCreated(null,
						"https://card.weibo.com/article/v5/editor#/draft"));
		WeiboPublicationStrategy strategy = new WeiboPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.WEIBO).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertNull(result.getRemoteContentId());
		assertEquals("https://card.weibo.com/article/v5/editor#/draft", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
