package org.huangry.colorful.geo.application.content;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformLoginStatus;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategy;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncClient;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncPublicationLoginStatusQuery;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证平台发现与通道状态合并；不依赖真实 CLI。
 */
class PublicationPlatformLoginServiceTest {

	/** 只展示已有策略的平台，查询一次并保留明确的登录结论。 */
	@Test
	void 应汇总已接入策略平台的登录状态() {
		WechatsyncPublicationLoginStatusQuery query = mock(WechatsyncPublicationLoginStatusQuery.class);
		when(query.listPlatformLoginStatuses()).thenReturn(Map.of(
				PublicationPlatformType.ZHIHU, new WechatsyncClient.WechatsyncLoginAccount(true, "见微"),
				PublicationPlatformType.JUEJIN, new WechatsyncClient.WechatsyncLoginAccount(false, null)));
		PublicationPlatformLoginService service = new PublicationPlatformLoginService(
				List.of(strategy(PublicationPlatformType.JUEJIN), strategy(PublicationPlatformType.ZHIHU),
						strategy(PublicationPlatformType.WEIBO)), query);

		List<PlatformLoginStatus> statuses = service.listPlatformLoginStatuses();

		assertEquals(3, statuses.size());
		assertEquals(PublicationLoginStatus.LOGGED_IN, statuses.get(0).status());
		assertEquals("见微", statuses.get(0).username());
		assertEquals(PublicationLoginStatus.NOT_LOGGED_IN, statuses.get(1).status());
		assertNull(statuses.get(1).username());
		assertEquals(PublicationLoginStatus.UNKNOWN, statuses.get(2).status());
		verify(query, times(1)).listPlatformLoginStatuses();
	}

	/** 通道失联不应误报为未登录。 */
	@Test
	void 通道查询失败时应返回未知() {
		WechatsyncPublicationLoginStatusQuery query = mock(WechatsyncPublicationLoginStatusQuery.class);
		when(query.listPlatformLoginStatuses()).thenThrow(new PublicationClientException("连接失败"));
		PublicationPlatformLoginService service = new PublicationPlatformLoginService(
				List.of(strategy(PublicationPlatformType.ZHIHU)), query);
		PlatformLoginStatus status = service.listPlatformLoginStatuses().get(0);
		assertEquals(PublicationLoginStatus.UNKNOWN, status.status());
		assertNull(status.username());
	}

	/** 策略替身只提供平台标识，发布动作不参与登录状态查询。 */
	private PublicationPlatformStrategy strategy(PublicationPlatformType platformType) {
		PublicationPlatformStrategy strategy = mock(PublicationPlatformStrategy.class);
		when(strategy.platformType()).thenReturn(platformType);
		return strategy;
	}
}
