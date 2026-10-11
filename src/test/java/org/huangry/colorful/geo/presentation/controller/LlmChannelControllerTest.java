package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.llm.LlmChannelBusiness;
import org.huangry.colorful.geo.application.llm.model.LlmChannelPage;
import org.huangry.colorful.geo.application.llm.model.LlmChannelResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 模型管理 HTTP 契约；覆盖保存、查询、启停与可读错误。 */
class LlmChannelControllerTest {
    private final LlmChannelBusiness business = mock(LlmChannelBusiness.class);
    private final MockMvc mvc = createMvc();

    /** 独立 MVC 测试注入模拟依赖，不启动数据库。 */
    private MockMvc createMvc() {
        var controller = new LlmChannelController();
        ReflectionTestUtils.setField(controller, "business", business);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 创建返回标识，列表采用有限分页，输入不能携带密钥字段。 */
    @Test
    void shouldCreateAndListWithoutCredentials() throws Exception {
        LlmChannelResponse c = new LlmChannelResponse(); c.setId(1L); c.setKeyConfigured(true);
        when(business.saveChannel(isNull(), any())).thenReturn(c);
        mvc.perform(post("/llm-channels").contentType("application/json").content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.apiKey").doesNotExist());
        when(business.listChannels(null, 0, 20)).thenReturn(new LlmChannelPage(List.of(c), 1, 0, 20));
        mvc.perform(get("/llm-channels")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    /** 只更新状态，空状态必须拒绝而不是默认停用。 */
    @Test
    void shouldChangeStateAndRejectMissingState() throws Exception {
        LlmChannelResponse c = new LlmChannelResponse(); c.setId(1L); c.setEnabled(false);
        when(business.changeEnabled(1L, false)).thenReturn(c);
        mvc.perform(patch("/llm-channels/1/enabled").contentType("application/json").content("{\"enabled\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(patch("/llm-channels/1/enabled").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        verify(business, times(1)).changeEnabled(1L, false);
    }

    /** 记录缺失和编码冲突提供稳定状态码，不暴露数据库内容。 */
    @Test
    void shouldReturnReadableMissingAndDuplicateErrors() throws Exception {
        when(business.getChannel(99)).thenThrow(new NoSuchElementException("模型渠道不存在"));
        mvc.perform(get("/llm-channels/99")).andExpect(status().isNotFound());
        when(business.saveChannel(isNull(), any())).thenThrow(new DuplicateKeyException("内部 SQL"));
        mvc.perform(post("/llm-channels").contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("渠道编码已存在"));
    }
}
