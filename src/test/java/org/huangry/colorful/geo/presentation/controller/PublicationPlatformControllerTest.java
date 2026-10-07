package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.content.PublicationPlatformLoginService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformLoginStatus;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 发布平台登录状态接口契约测试；仅验证只读响应，不调用真实桥接。
 */
class PublicationPlatformControllerTest {

	/** 平台选择列表不应执行可能等待浏览器扩展的登录查询。 */
	@Test
	void 应快速返回已接入平台选项() throws Exception {
		PublicationPlatformLoginService service = mock(PublicationPlatformLoginService.class);
		when(service.listConfiguredPlatforms()).thenReturn(List.of(
				new PublicationPlatformOption(PublicationPlatformType.ZHIHU, "知乎")));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicationPlatformController(service)).build();

		mvc.perform(get("/publication/platforms"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].platformType").value("ZHIHU"))
				.andExpect(jsonPath("$[0].displayName").value("知乎"));
		verify(service, never()).listPlatformLoginStatuses();
	}

	/** GET 应返回平台、登录状态和可选账号名。 */
	@Test
	void 应返回已接入平台的登录状态() throws Exception {
		PublicationPlatformLoginService service = mock(PublicationPlatformLoginService.class);
		when(service.listPlatformLoginStatuses()).thenReturn(List.of(
				new PlatformLoginStatus(PublicationPlatformType.ZHIHU, "知乎",
						PublicationLoginStatus.LOGGED_IN, "见微")));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicationPlatformController(service)).build();

		mvc.perform(get("/publication/platforms/login-status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].platformType").value("ZHIHU"))
				.andExpect(jsonPath("$[0].displayName").value("知乎"))
				.andExpect(jsonPath("$[0].status").value("LOGGED_IN"))
				.andExpect(jsonPath("$[0].username").value("见微"));
		verify(service, times(1)).listPlatformLoginStatuses();
	}
}
