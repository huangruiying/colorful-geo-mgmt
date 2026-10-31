package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeover;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverFrame;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverInput;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 浏览器人工接管窗口 HTTP 契约：列表、画面、操作、放弃及结束后的 404。 */
class MgmtBrowserTakeoverControllerTest {

    private final BrowserTakeoverRegistry registry = mock(BrowserTakeoverRegistry.class);
    private final BrowserTakeover takeover = mock(BrowserTakeover.class);
    private final MockMvc mvc = createMvc();

    /** 独立 MVC 测试注入登记表替身，带全局异常映射。 */
    private MockMvc createMvc() {
        var controller = new MgmtBrowserTakeoverController();
        ReflectionTestUtils.setField(controller, "registry", registry);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 列表给出调用方的标题、原因和期限，不含画面。 */
    @Test
    void shouldListPendingTakeoversWithTitleAndReason() throws Exception {
        when(takeover.getTakeoverId()).thenReturn("id-1");
        when(takeover.getTitle()).thenReturn("搜狐号 · 预发布");
        when(takeover.getReason()).thenReturn("搜狐号要求对本浏览器做短信安全验证");
        when(takeover.getStartedAt()).thenReturn(Instant.parse("2026-10-31T02:00:00Z"));
        when(takeover.getExpiresAt()).thenReturn(Instant.parse("2026-10-31T02:10:00Z"));
        when(registry.listTakeovers()).thenReturn(List.of(takeover));

        mvc.perform(get("/browser-takeovers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].takeoverId").value("id-1"))
                .andExpect(jsonPath("$[0].title").value("搜狐号 · 预发布"))
                .andExpect(jsonPath("$[0].reason").value("搜狐号要求对本浏览器做短信安全验证"))
                .andExpect(jsonPath("$[0].imageBase64").doesNotExist());
    }

    /** 第一帧还没到时返回 204，到达后返回画面与序号。 */
    @Test
    void shouldReturnNoContentBeforeFirstFrame() throws Exception {
        when(registry.getTakeover("id-1")).thenReturn(takeover);
        when(takeover.readLatestFrame()).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new BrowserTakeoverFrame("aW1n", 1280, 900, 3)));

        mvc.perform(get("/browser-takeovers/id-1/frame")).andExpect(status().isNoContent());
        mvc.perform(get("/browser-takeovers/id-1/frame")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(3))
                .andExpect(jsonPath("$.width").value(1280));
    }

    /** 接管已结束时返回 404，窗口据此关闭。 */
    @Test
    void shouldReturnNotFoundWhenTakeoverFinished() throws Exception {
        when(registry.getTakeover("gone")).thenThrow(new NoSuchElementException("人工接管已结束或不存在"));

        mvc.perform(get("/browser-takeovers/gone/frame")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("人工接管已结束或不存在"));
    }

    /** 操作原样交给接管记录；不合法时 400。 */
    @Test
    void shouldForwardInputAndRejectInvalidInput() throws Exception {
        when(registry.getTakeover("id-1")).thenReturn(takeover);

        mvc.perform(post("/browser-takeovers/id-1/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MOUSE_PRESSED\",\"x\":10,\"y\":20}"))
                .andExpect(status().isNoContent());
        verify(takeover).addInput(new BrowserTakeoverInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 10, 20, 0, 0, null, null));

        doThrow(new IllegalArgumentException("不支持的按键")).when(takeover).addInput(any());
        mvc.perform(post("/browser-takeovers/id-1/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"KEY_PRESS\",\"key\":\"F5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("不支持的按键"));
    }

    /** 放弃交给接管记录，原业务随后按失败处理。 */
    @Test
    void shouldAbandonTakeover() throws Exception {
        when(registry.getTakeover("id-1")).thenReturn(takeover);

        mvc.perform(post("/browser-takeovers/id-1/abandon")).andExpect(status().isNoContent());

        verify(takeover).abandon();
    }
}
