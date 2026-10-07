package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.baijiahao;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.baijiahao.BaijiahaoBrowserClient;
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

/** 验证百家号策略只在浏览器客户端确认草稿后返回预发布成功。 */
class BaijiahaoPublicationStrategyTest {

	/** 已确认草稿的标识和编辑链接必须原样传给上层。 */
	@Test
	void 应返回浏览器确认的草稿标识与链接() {
		BaijiahaoBrowserClient client = mock(BaijiahaoBrowserClient.class);
		when(client.createDraft("测试标题", "测试正文"))
				.thenReturn(new BaijiahaoBrowserClient.DraftCreated("123",
						"https://baijiahao.baidu.com/builder/rc/edit?type=news&id=123"));
		BaijiahaoPublicationStrategy strategy = new BaijiahaoPublicationStrategy(client);

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.BAIJIAHAO).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertEquals("123", result.getRemoteContentId());
		assertEquals("https://baijiahao.baidu.com/builder/rc/edit?type=news&id=123", result.getDraftUrl());
		verify(client).createDraft("测试标题", "测试正文");
	}
}
