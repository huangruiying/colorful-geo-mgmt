package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xiaohongshu;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证小红书预发布只确认系统已保存确认稿，不在小红书创建草稿，且不开放公开发布。 */
class XiaohongshuPublicationStrategyTest {

	/** 小红书没有云端草稿：预发布直接成功，远程 ID 与草稿链接为空，并说明确认稿保存在系统中。 */
	@Test
	void 预发布只确认系统已保存确认稿() {
		XiaohongshuPublicationStrategy strategy = new XiaohongshuPublicationStrategy();

		PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
				.platformType(PublicationPlatformType.XIAOHONGSHU).title("测试标题").content("测试正文").build());

		assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
		assertNull(result.getRemoteContentId());
		assertNull(result.getDraftUrl());
		assertEquals("小红书没有云端草稿，确认稿已保存在系统中，发布时将直接写入并发布", result.getMessage());
	}

	/** 公开发布未接入，不能把确认稿误标成已发布。 */
	@Test
	void 公开发布未接入时拒绝() {
		XiaohongshuPublicationStrategy strategy = new XiaohongshuPublicationStrategy();

		assertThrows(PublicationClientException.class, () -> strategy.publish(PrePublicationRequest.builder()
				.platformType(PublicationPlatformType.XIAOHONGSHU).remoteContentId("1").build()));
	}
}
