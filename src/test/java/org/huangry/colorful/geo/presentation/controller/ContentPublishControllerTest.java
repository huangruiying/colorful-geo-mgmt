package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.content.ContentPublishBusiness;
import org.huangry.colorful.geo.application.content.model.ContentPublishRecordPage;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布接口路由测试；按当前发布路径区分预发布、记录列表和删除动作。
 */
class ContentPublishControllerTest {

    private ContentPublishBusiness publishBusiness;
    private MockMvc mockMvc;

    /** 每个用例独立创建发布业务替身，不调用平台和数据库。 */
    @BeforeEach
    void setUp() {
        publishBusiness = mock(ContentPublishBusiness.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ContentPublishController(publishBusiness)).build();
    }

    /** 预发布使用内容优化记录 ID，路径与发布 Controller 保持一致。 */
    @Test
    void 预发布使用当前发布路径() throws Exception {
        when(publishBusiness.prePublish(7L, "标题", "正文", List.of(PublicationPlatformType.ZHIHU)))
                .thenReturn(List.of());
        mockMvc.perform(post("/content-publish/7/pre-publish")
                        .contentType("application/json")
                        .content("{\"publicationTitle\":\"标题\",\"publicationContent\":\"正文\",\"platformTypes\":[\"ZHIHU\"]}"))
                .andExpect(status().isOk());

        verify(publishBusiness).prePublish(7L, "标题", "正文", List.of(PublicationPlatformType.ZHIHU));
    }

    /** 发布记录列表使用当前发布路径与分页参数。 */
    @Test
    void 发布列表使用当前发布路径() throws Exception {
        when(publishBusiness.listContentPublishRecords(0, 20))
                .thenReturn(new ContentPublishRecordPage(List.of(), 0, 0, 20));

        mockMvc.perform(get("/content-publish/list"))
                .andExpect(status().isOk());

        verify(publishBusiness).listContentPublishRecords(0, 20);
    }

    /** 删除只针对本地发布记录，并返回无正文的成功状态。 */
    @Test
    void 删除使用当前发布路径() throws Exception {
        mockMvc.perform(delete("/content-publish/detail/11"))
                .andExpect(status().isNoContent());

        verify(publishBusiness).deleteContentPublishRecord(11L);
    }
}
