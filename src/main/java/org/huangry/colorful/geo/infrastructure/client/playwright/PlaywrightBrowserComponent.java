package org.huangry.colorful.geo.infrastructure.client.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Component;


/**
 * 管理隔离浏览器会话的创建与释放；不处理平台页面、登录结论或发布结果。
 * 返回的会话只能在创建它的浏览器工作线程中使用和关闭。
 */
@Component
public class PlaywrightBrowserComponent {

    /**
     * 打开隔离的 Chromium 上下文；调用方负责在同一线程关闭返回的会话。
     *
     * @param contextOptions 本次会话的视口或已保存登录态
     * @return 浏览器会话
     */
    public BrowserSession openBrowser(Browser.NewContextOptions contextOptions) {
        Playwright playwright = Playwright.create();
        Browser browser = null;
        try {
            browser = playwright.chromium().launch();
            BrowserContext context = browser.newContext(contextOptions);
            return new BrowserSession(playwright, browser, context);
        } catch (RuntimeException exception) {
            // 创建中途失败时同样释放已打开的浏览器，避免残留 Chromium 进程。
            try {
                if (browser != null) browser.close();
            } catch (RuntimeException closeException) {
                exception.addSuppressed(closeException);
            } finally {
                try {
                    playwright.close();
                } catch (RuntimeException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }

    /**
     * 一次登录或核验使用的浏览器资源；只提供页面、上下文和确定的关闭顺序。
     */
    public static final class BrowserSession implements AutoCloseable {
        private final Playwright playwright;
        private final Browser browser;
        private final BrowserContext context;

        private BrowserSession(Playwright playwright, Browser browser, BrowserContext context) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
        }

        /** @return 本次隔离上下文，供平台核验账号与导出登录态。 */
        public BrowserContext context() {
            return context;
        }

        /** @return 当前上下文中的新页面，供平台打开登录入口。 */
        public Page newPage() {
            return context.newPage();
        }

        /** @return 本次会话的 Cookie 与 IndexedDB 状态，仅供服务端保存。 */
        public String exportStorageState() {
            return context.storageState(new BrowserContext.StorageStateOptions().setIndexedDB(true));
        }

    /** 按上下文、可选的独立浏览器、驱动的顺序释放资源。 */
        @Override
        public void close() {
            try {
                context.close();
            } finally {
                try {
                    if (browser != null) browser.close();
                } finally {
                    playwright.close();
                }
            }
        }
    }
}
