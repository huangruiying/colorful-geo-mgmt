package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.domain.model.ContentDraftPreview;
import org.huangry.colorful.geo.domain.service.publish.ContentDraftPreviewService;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证草稿查看接口的路由、禁止缓存及可读失败响应。 */
class ContentDraftPreviewControllerTest {
    private final ContentDraftPreviewService service = mock(ContentDraftPreviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContentDraftPreviewController(service)).build();

    /** 草稿内容只返回查看数据，不返回会话链接，并禁止浏览器缓存。 */
    @Test
    void 返回当前草稿且禁止缓存() throws Exception {
        when(service.readDraft(48L)).thenReturn(ContentDraftPreview.builder().publishRecordId(48L)
                .title("平台标题").content("正文").screenshotBase64("cG5n").build());
        mvc.perform(post("/content-publish/detail/48/draft-preview"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("平台标题"))
                .andExpect(jsonPath("$.draftUrl").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /** 平台异常、参数错误与记录不存在分别返回明确状态。 */
    @Test
    void 查看失败给出可读错误() throws Exception {
        when(service.readDraft(1L)).thenThrow(new PublicationClientException("请重新登录"));
        when(service.readDraft(2L)).thenThrow(new NoSuchElementException("发布记录不存在"));
        when(service.readDraft(0L)).thenThrow(new IllegalArgumentException("发布记录 ID 必须大于零"));
        mvc.perform(post("/content-publish/detail/1/draft-preview")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("请重新登录"));
        mvc.perform(post("/content-publish/detail/2/draft-preview")).andExpect(status().isNotFound());
        mvc.perform(post("/content-publish/detail/0/draft-preview")).andExpect(status().isBadRequest());
    }
}
