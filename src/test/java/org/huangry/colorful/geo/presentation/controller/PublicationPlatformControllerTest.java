package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.content.PublicationPlatformLoginService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 发布平台只读接口契约测试；仅验证平台选项响应，不调用真实桥接。
 */
class PublicationPlatformControllerTest {

	/** 平台选择列表只返回已接入策略的平台选项。 */
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
	}
}
