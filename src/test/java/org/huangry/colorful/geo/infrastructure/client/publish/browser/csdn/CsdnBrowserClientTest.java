package org.huangry.colorful.geo.infrastructure.client.publish.browser.csdn;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 验证 CSDN 登录态边界、Markdown 完整写入与平台草稿保存结果。 */
class CsdnBrowserClientTest {

    /** 标题须确认输入、正文须保留 Markdown 换行，成功响应后仍须重开草稿全文核验。 */
    @Test
    void 创建草稿应确认标题并粘贴完整Markdown() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus("LOGGED_IN");
        saved.setStorageState("{\"cookies\":[]}");
        when(loginDao.findByPlatform("CSDN")).thenReturn(saved);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var session = mock(PlaywrightBrowserComponent.BrowserSession.class);
        Page page = mock(Page.class);
        Locator titleInput = mock(Locator.class);
        Locator editor = mock(Locator.class);
        Locator titleDisplay = mock(Locator.class);
        Locator saveButton = mock(Locator.class);
        Keyboard keyboard = mock(Keyboard.class);
        Response response = mock(Response.class);
        when(browser.openBrowser(any(Browser.NewContextOptions.class))).thenReturn(session);
        when(session.newPage()).thenReturn(page);
        when(page.locator(".article-bar__title-display")).thenReturn(titleDisplay);
        when(page.locator("input[placeholder*='请输入文章标题']")).thenReturn(titleInput);
        when(page.locator("pre.editor__inner[contenteditable=true]")).thenReturn(editor);
        when(page.locator("button.btn-save")).thenReturn(saveButton);
        when(page.keyboard()).thenReturn(keyboard);
        when(page.waitForResponse(any(Predicate.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return response;
        });
        when(response.text()).thenReturn("{\"code\":200,\"data\":{\"id\":123456}} ");
        String markdown = "# 测试\n\n- 第一项\n- 第二项";
        var client = new CsdnBrowserClient(loginDao, browser, new PublicationBrowserProperties());

        var result = client.createDraft("预发布测试标题", markdown);

        assertEquals("123456", result.remoteContentId());
        assertEquals("https://editor.csdn.net/md?articleId=123456", result.draftUrl());
        var order = inOrder(titleInput, editor, keyboard, saveButton);
        order.verify(titleInput).fill("预发布测试标题");
        order.verify(titleInput).press("Enter");
        order.verify(editor).click();
        order.verify(keyboard).press("ControlOrMeta+A");
        order.verify(keyboard).press("Backspace");
        order.verify(editor).evaluate(any(String.class), eq(markdown));
        order.verify(saveButton).click();
        verify(page).navigate(eq(result.draftUrl()), any(Page.NavigateOptions.class));
        verify(page).waitForFunction(any(String.class), eq(List.of("预发布测试标题", markdown)),
                any(Page.WaitForFunctionOptions.class));
        verify(session).close();
    }

    /** 保存响应提供真实数字 ID 时，不依赖编辑页跳转。 */
    @Test
    void 保存成功应读取平台草稿标识() {
        assertEquals("167396276", CsdnBrowserClient.readSavedDraftId(
                "{\"code\":200,\"data\":{\"id\":167396276},\"msg\":\"success\"}"));
    }

    /** HTTP 成功但平台限流时，应返回明确拒绝原因而不是等待 URL 超时。 */
    @Test
    void 保存被限流应返回平台失败原因() {
        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> CsdnBrowserClient.readSavedDraftId(
                        "{\"code\":400,\"data\":null,\"msg\":\"文章频繁发布，请稍后再试\"}"));
        assertEquals("CSDN 草稿保存失败：文章频繁发布，请稍后再试", exception.getMessage());
    }

    /** 平台已受理但未给出草稿标识时，应保留结果未知语义。 */
    @Test
    void 保存成功但缺少标识应禁止误报成功() {
        assertThrows(PublicationOutcomeUnknownException.class,
                () -> CsdnBrowserClient.readSavedDraftId("{\"code\":200,\"data\":{}}"));
    }

    /** 没有 CSDN 登录记录时，不应启动浏览器。 */
    @Test
    void 未保存登录态应拒绝创建草稿() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        CsdnBrowserClient client = new CsdnBrowserClient(loginDao, browser, new PublicationBrowserProperties());

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> client.createDraft("标题", "正文"));

        assertEquals("CSDN 浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
        verifyNoInteractions(browser);
    }

    /** 已失效记录即使保留 storageState，也不能再次用于发布。 */
    @Test
    void 已失效登录态应拒绝创建草稿() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus("EXPIRED");
        saved.setStorageState("{\"cookies\":[]}");
        when(loginDao.findByPlatform("CSDN")).thenReturn(saved);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        CsdnBrowserClient client = new CsdnBrowserClient(loginDao, browser, new PublicationBrowserProperties());

        assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));

        verifyNoInteractions(browser);
    }
}
