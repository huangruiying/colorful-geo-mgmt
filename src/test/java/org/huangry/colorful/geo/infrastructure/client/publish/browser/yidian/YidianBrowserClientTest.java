package org.huangry.colorful.geo.infrastructure.client.publish.browser.yidian;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证一点号存草稿结果解析、标题长度范围与登录态边界。 */
class YidianBrowserClientTest {

    /** 存草稿接口返回正数 id 时取得草稿 ID。 */
    @Test
    void 保存成功时返回草稿ID() {
        assertEquals("1141534006604800", YidianBrowserClient.readSavedDraftId("{\"id\":1141534006604800}"));
    }

    /** 响应中没有 id 时结果未确认。 */
    @Test
    void 缺少草稿ID时结果未确认() {
        assertThrows(PublicationOutcomeUnknownException.class,
                () -> YidianBrowserClient.readSavedDraftId("{\"status\":\"failed\"}"));
    }

    /** 标题不在 5～64 字时在打开浏览器前拒绝。 */
    @Test
    void 标题长度不符时拒绝() {
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new YidianBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", mock(PlatformBrowserLoginDao.class));
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        var exception = assertThrows(PublicationClientException.class, () -> client.createDraft("四个字的", "正文"));

        assertEquals("一点号文章标题需 5～64 字，当前 4 字", exception.getMessage());
        verifyNoInteractions(browser);
    }

    /** 数据库没有登录记录时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new YidianBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", mock(PlatformBrowserLoginDao.class));
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        assertThrows(PublicationClientException.class, () -> client.createDraft("五个字标题", "正文"));
        verifyNoInteractions(browser);
    }
}
