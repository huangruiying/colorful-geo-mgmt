package org.huangry.colorful.geo.infrastructure.client.wechatsync;

import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 批量登录状态的 CLI 解析与平台映射测试；不访问真实浏览器扩展。
 */
class WechatsyncLoginStatusTest {

	/** 扩展明确返回的状态和账号名应被保留。 */
	@Test
	void 应解析已登录未登录及账号名() {
		WechatsyncClient client = new WechatsyncClient(new WechatsyncProperties());
		String output = "✔ Chrome Extension 已连接\n支持的平台 (2):\n"
				+ "  zhihu           知乎       ✓ 已登录 (见微)\n"
				+ "  juejin          掘金       ✗ 未登录\n";
		Map<String, WechatsyncClient.WechatsyncLoginAccount> statuses =
				client.parsePlatformLoginStatuses(output);
		assertTrue(statuses.get("zhihu").loggedIn());
		assertEquals("见微", statuses.get("zhihu").username());
		assertFalse(statuses.get("juejin").loggedIn());
		assertNull(statuses.get("juejin").username());
	}

	/** CLI 可能失败后以零退出，因此缺少平台列表不能当作所有平台未登录。 */
	@Test
	void 无有效平台列表时应拒绝推测() {
		WechatsyncClient client = new WechatsyncClient(new WechatsyncProperties());
		assertThrows(PublicationClientException.class,
				() -> client.parsePlatformLoginStatuses("✖ 获取失败\n桥接不可用"));
	}

	/** 查询使用一个 platforms --auth 命令，并沿用原有 Token 环境传递方式。 */
	@Test
	void 应构造批量登录查询命令() {
		WechatsyncProperties properties = new WechatsyncProperties();
		properties.setExecutable("/bin/echo");
		properties.setToken("test-token");
		WechatsyncClient client = new WechatsyncClient(properties);
		ProcessBuilder command = client.createListLoginStatusesCommand();
		assertEquals("platforms", command.command().get(3));
		assertEquals("--auth", command.command().get(4));
		assertFalse(command.command().contains("test-token"));
	}

	/** 平台映射仅转换已返回的平台，未返回的平台交给上层标记未知。 */
	@Test
	void 应按现有发布平台映射登录结果() {
		WechatsyncClient client = mock(WechatsyncClient.class);
		when(client.listPlatformLoginStatuses()).thenReturn(Map.of(
				"weixin", new WechatsyncClient.WechatsyncLoginAccount(true, "公众号"),
				"51cto", new WechatsyncClient.WechatsyncLoginAccount(false, null)));
		Map<PublicationPlatformType, WechatsyncClient.WechatsyncLoginAccount> statuses =
				new WechatsyncPublicationLoginStatusQuery(client).listPlatformLoginStatuses();
		assertTrue(statuses.get(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT).loggedIn());
		assertFalse(statuses.get(PublicationPlatformType.CTO_51).loggedIn());
		assertFalse(statuses.containsKey(PublicationPlatformType.ZHIHU));
		verify(client, times(1)).listPlatformLoginStatuses();
	}

	/** 本地替身进程验证查询结果在一次调用内返回，不需要真实扩展。 */
	@Test
	@EnabledOnOs({OS.MAC, OS.LINUX})
	void 批量查询应执行一次命令() {
		WechatsyncClient client = spy(new WechatsyncClient(new WechatsyncProperties()));
		doReturn(new ProcessBuilder("/bin/echo", "✔ Chrome Extension 已连接\n支持的平台 (1):\n"
				+ "  zhihu  知乎  ✓ 已登录 (见微)"))
				.when(client).createListLoginStatusesCommand();
		assertTrue(client.listPlatformLoginStatuses().get("zhihu").loggedIn());
		verify(client, times(1)).createListLoginStatusesCommand();
	}

	/** 首次连接超时后只重查登录状态，不执行正文同步或访问真实 Chrome。 */
	@Test
	@EnabledOnOs(OS.MAC)
	void 扩展休眠时唤醒后重查登录状态() {
		WechatsyncProperties properties = new WechatsyncProperties();
		properties.setAuthTimeoutSeconds(1);
		WechatsyncClient client = spy(new WechatsyncClient(properties));
		doReturn(new ProcessBuilder("/bin/sh", "-c", "sleep 3"),
				new ProcessBuilder("/bin/echo", "✔ Chrome Extension 已连接\n支持的平台 (1):\n"
						+ "  zhihu  知乎  ✓ 已登录 (见微)"))
				.when(client).createListLoginStatusesCommand();
		doReturn(new ProcessBuilder("/usr/bin/true"))
				.when(client).createWakeChromeExtensionCommand();

		assertTrue(client.listPlatformLoginStatuses().get("zhihu").loggedIn());
		verify(client, times(2)).createListLoginStatusesCommand();
		verify(client, times(1)).createWakeChromeExtensionCommand();
	}
}
