package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.toutiao;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.toutiao.ToutiaoBrowserClient;
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

/** 验证今日头条策略只在浏览器客户端确认草稿后返回预发布成功。 */
class ToutiaoPublicationStrategyTest {

	/** 草稿 URL 可能不含 id，此时远程标识为空，但草稿链接与状态仍应返回。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		ToutiaoBrowserClient client = mock(ToutiaoBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new ToutiaoBrowserClient.DraftCreated(null,
						"https://mp.toutiao.com/profile_v4/graphic/publish"));
		ToutiaoPublicationStrategy strategy = new ToutiaoPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.TOUTIAO).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertNull(result.getRemoteContentId());
		assertEquals("https://mp.toutiao.com/profile_v4/graphic/publish", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
