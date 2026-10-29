package org.huangry.colorful.geo.infrastructure.client.publish.browser.bilibili;

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

/** 验证哔哩哔哩登录态边界、草稿保存结果解析与正文回读比较。 */
class BilibiliBrowserClientTest {

    /** 保存接口 code 为 0 且返回正数 article_id 才算保存成功。 */
    @Test
    void 保存成功时返回草稿ID() {
        assertEquals("387105", BilibiliBrowserClient.readSavedDraftId(
                "{\"code\":0,\"message\":\"OK\",\"data\":{\"article_id\":387105}}"));
    }

    /** 平台明确拒绝时带上平台原因，不能当成结果未知。 */
    @Test
    void 平台拒绝保存时说明原因() {
        var exception = assertThrows(PublicationClientException.class,
                () -> BilibiliBrowserClient.readSavedDraftId("{\"code\":-101,\"message\":\"账号未登录\"}"));

        assertEquals("哔哩哔哩草稿保存失败：账号未登录", exception.getMessage());
    }

    /** 保存成功但没有有效草稿 ID 时结果未确认，不能报确定失败。 */
    @Test
    void 缺少草稿ID时结果未确认() {
        var exception = assertThrows(PublicationClientException.class,
                () -> BilibiliBrowserClient.readSavedDraftId("{\"code\":0,\"data\":{}}"));

        assertInstanceOf(PublicationOutcomeUnknownException.class, exception);
    }

    /** 编辑器在段落间显示的空行不算差异，文字变化仍算差异。 */
    @Test
    void 比较正文时忽略段落间空行() {
        assertEquals(BilibiliBrowserClient.normalizeParagraphs("第一段\n\n- 列表项"),
                BilibiliBrowserClient.normalizeParagraphs("\n\n\n第一段\n\n\n\n- 列表项"));
        assertEquals("第一段\n第二段", BilibiliBrowserClient.normalizeParagraphs("第一段\n\n第二段\n"));
    }

    /** 数据库里不是已登录状态时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus("EXPIRED");
        saved.setStorageState("{\"cookies\":[]}");
        when(loginDao.findByPlatform("BILIBILI")).thenReturn(saved);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new BilibiliBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", loginDao);
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        var exception = assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));

        assertEquals("哔哩哔哩浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
        verifyNoInteractions(browser);
    }
}
