package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.test.TestPublistBusiness;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台预发布测试入口的 HTTP 绑定测试。
 *
 * <p>逐个覆盖已接入的策略平台和多平台参数；仅模拟应用层，不连接浏览器或创建真实草稿。</p>
 *
 * @author huangry
 */
@ExtendWith(MockitoExtension.class)
class TestControllerPublicationTest {

    @Mock
    private TestPublistBusiness testPublistBusiness;

    @InjectMocks
    private TestController controller;

    private MockMvc mockMvc;

    /** 使用独立 MVC 环境验证路由和参数绑定，不启动真实服务。 */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 已接入的每个策略均可通过同一入口按平台枚举单独测试。 */
    @ParameterizedTest
    @EnumSource(PublicationPlatformType.class)
    void 每个已接入平台都应有独立预发布测试路径(PublicationPlatformType platformType) throws Exception {
        List<PublicationResult> result = List.of(PublicationResult.builder()
                .platformType(platformType).status(PublicationTaskStatus.DRAFT_CREATED).build());
        when(testPublistBusiness.testPrePublish("测试标题", "测试正文", List.of(platformType)))
                .thenReturn(result);

        mockMvc.perform(post("/test/prePublish/{platformType}", platformType.name())
                        .param("title", "测试标题").param("text", "测试正文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].platformType").value(platformType.name()))
                .andExpect(jsonPath("$[0].status").value("DRAFT_CREATED"));

        verify(testPublistBusiness).testPrePublish("测试标题", "测试正文", List.of(platformType));
    }

    /** 批量入口将同一标题和正文连同多个平台交给应用层，不自行循环调用。 */
    @Test
    void 多平台入口应透传统一内容和平台列表() throws Exception {
        List<PublicationPlatformType> platforms = List.of(PublicationPlatformType.JUEJIN, PublicationPlatformType.CSDN);
        List<PublicationResult> result = List.of(
                PublicationResult.builder().platformType(PublicationPlatformType.JUEJIN)
                        .status(PublicationTaskStatus.DRAFT_CREATED).build(),
                PublicationResult.builder().platformType(PublicationPlatformType.CSDN)
                        .status(PublicationTaskStatus.FAILED).build());
        when(testPublistBusiness.testPrePublish("同一标题", "同一正文", platforms)).thenReturn(result);

        mockMvc.perform(post("/test/prePublishBatch")
                        .param("platformTypes", "JUEJIN,CSDN")
                        .param("title", "同一标题").param("text", "同一正文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].platformType").value("JUEJIN"))
                .andExpect(jsonPath("$[1].platformType").value("CSDN"))
                .andExpect(jsonPath("$[1].status").value("FAILED"));

        verify(testPublistBusiness).testPrePublish("同一标题", "同一正文", platforms);
    }
}
