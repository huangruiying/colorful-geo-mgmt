package org.huangry.colorful.geo.infrastructure.client.publish.browser.sohu;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证搜狐号登录态边界、存草稿结果解析与正文回读比较。 */
class SohuBrowserClientTest {

    /** 存草稿接口 success 为 true 且 data 是正数草稿 ID 才算保存成功。 */
    @Test
    void 保存成功时返回草稿ID() {
        assertEquals("1094637762", SohuBrowserClient.readSavedDraftId(
                "{\"data\":1094637762,\"code\":2000000,\"msg\":\"\",\"success\":true}"));
    }

    /** 平台明确拒绝时带上平台原因，不能当成结果未知。 */
    @Test
    void 平台拒绝保存时说明原因() {
        var exception = assertThrows(PublicationClientException.class,
                () -> SohuBrowserClient.readSavedDraftId("{\"code\":1211,\"msg\":\"登录错误\",\"success\":false}"));

        assertEquals("搜狐号草稿保存失败：登录错误", exception.getMessage());
    }

    /** 保存成功但没有有效草稿 ID 时结果未确认，不能报确定失败。 */
    @Test
    void 缺少草稿ID时结果未确认() {
        var exception = assertThrows(PublicationClientException.class,
                () -> SohuBrowserClient.readSavedDraftId("{\"code\":2000000,\"success\":true}"));

        assertInstanceOf(PublicationOutcomeUnknownException.class, exception);
    }

    /** 编辑器在段落间显示的空行不算差异。 */
    @Test
    void 比较正文时忽略段落间空行() {
        assertEquals(SohuBrowserClient.normalizeParagraphs("第一段\n\n- 列表项"),
                SohuBrowserClient.normalizeParagraphs("第一段\n\n\n\n\n- 列表项\n"));
    }

    /** 数据库里不是已登录状态时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus("NOT_LOGGED_IN");
        when(loginDao.findByPlatform("SOHU")).thenReturn(saved);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new SohuBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", loginDao);
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        var exception = assertThrows(PublicationClientException.class, () -> client.createDraft("标题测试", "正文"));

        assertEquals("搜狐号浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
        verifyNoInteractions(browser);
    }
}
