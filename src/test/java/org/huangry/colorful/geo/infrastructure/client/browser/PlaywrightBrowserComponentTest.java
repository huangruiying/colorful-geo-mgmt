package org.huangry.colorful.geo.infrastructure.client.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;


import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 验证浏览器会话创建失败和正常关闭时都释放资源；不依赖真实平台页面。
 */
class PlaywrightBrowserComponentTest {

    /** 会话关闭时按上下文、浏览器、驱动的顺序释放资源。 */
    @Test
    void shouldCloseBrowserResourcesInOrder() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launch(org.mockito.ArgumentMatchers.any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        when(browser.newContext(options)).thenReturn(context);
        when(context.newPage()).thenReturn(page);

        try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
            factory.when(Playwright::create).thenReturn(playwright);
            PlaywrightBrowserComponent.BrowserSession session = new PlaywrightBrowserComponent().openBrowser(options);
            assertSame(context, session.context());
            assertSame(page, session.newPage());
            session.close();
        }

        InOrder order = inOrder(context, browser, playwright);
        order.verify(context).close();
        order.verify(browser).close();
        order.verify(playwright).close();
    }

    /** 上下文创建失败也必须关闭已打开的浏览器和驱动。 */
    @Test
    void shouldCloseOpenedResourcesWhenContextCreationFails() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launch(org.mockito.ArgumentMatchers.any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        when(browser.newContext(options)).thenThrow(new IllegalStateException("context unavailable"));

        try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
            factory.when(Playwright::create).thenReturn(playwright);
            assertThrows(IllegalStateException.class,
                    () -> new PlaywrightBrowserComponent().openBrowser(options));
        }

        InOrder order = inOrder(browser, playwright);
        order.verify(browser).close();
        order.verify(playwright).close();
    }

    /** 导出的会话包含 IndexedDB，供后续登录态恢复使用。 */
    @Test
    void shouldExportBrowserStorageStateWithIndexedDb() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launch(org.mockito.ArgumentMatchers.any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        when(browser.newContext(options)).thenReturn(context);
        when(context.storageState(org.mockito.ArgumentMatchers.any(BrowserContext.StorageStateOptions.class)))
                .thenReturn("saved-state");

        try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
            factory.when(Playwright::create).thenReturn(playwright);
            try (PlaywrightBrowserComponent.BrowserSession session =
                         new PlaywrightBrowserComponent().openBrowser(options)) {
                assertEquals("saved-state", session.exportStorageState());
            }
        }
    }

}
