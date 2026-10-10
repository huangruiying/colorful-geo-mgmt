package org.huangry.colorful.geo.regtest.diag;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.huangry.colorful.geo.regtest.PlatformLoginState;

import java.lang.ProcessBuilder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 决定性实验：CDP 连真实 Chrome（webdriver=false，无 --enable-automation），看小红书是否真保存。
 * 两种模式：
 *   cdp-headless  -> 真实 Chrome 二进制 + --headless=new（真实渲染，但无窗口）
 *   cdp-headed    -> 真实 Chrome 二进制 + 真实窗口
 * 两者 webdriver 均为 false，用于把「自动化标记」与「无头渲染」两个变量拆开。
 */
public class XiaohongshuCdpProbe {
    private static final String CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";
    private static final int PORT = 9222;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "cdp-headless";
        boolean headed = "cdp-headed".equals(mode);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSCDP" + ts;
        String title = "[CDP探针]" + token;
        String content = "CDP真实Chrome保存测试，" + token + "。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[CDP][error] 无登录态"); System.exit(2);
        }
        String storageState = login.getStorageState();
        System.out.println("[CDP] 账号=" + login.getAccountName() + " 模式=" + mode + " 标记=" + token);

        List<String> cmd = new ArrayList<>();
        cmd.add(CHROME);
        cmd.add("--remote-debugging-port=" + PORT);
        cmd.add("--remote-debugging-address=127.0.0.1");
        cmd.add("--user-data-dir=/tmp/xhsreal");
        cmd.add("--no-first-run");
        cmd.add("--no-sandbox");
        cmd.add("--no-proxy-server");
        if (!headed) cmd.add("--headless=new");
        Process chrome = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        boolean up = false;
        for (int i = 0; i < 40; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/json/version").openConnection();
                c.setConnectTimeout(500); c.setReadTimeout(500);
                if (c.getResponseCode() == 200) { up = true; break; }
            } catch (Exception e) { /* retry */ }
            Thread.sleep(500);
        }
        if (!up) {
            System.out.println("[CDP][error] Chrome 调试端口未起来（可能无 display 或二进制缺失）");
            chrome.destroyForcibly(); System.exit(3);
        }
        System.out.println("[CDP] 已连上真实 Chrome 调试端口");

        List<String> savePosts = new CopyOnWriteArrayList<>();
        List<String> tokenResp = new CopyOnWriteArrayList<>();
        AtomicBoolean appeared = new AtomicBoolean(false);
        Object wd = "?";
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().connectOverCDP("http://127.0.0.1:" + PORT);
             BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            page.onRequest(req -> {
                String u = req.url().toLowerCase();
                if (req.method().equals("POST") && (u.contains("save") || u.contains("draft")
                        || u.contains("note") || u.contains("publish") || u.contains("editing"))) {
                    savePosts.add(req.method() + " " + req.url());
                }
            });
            page.onResponse(resp -> {
                try {
                    String b = resp.text();
                    if (b != null && b.contains(token)) { tokenResp.add("RESP " + resp.status() + " " + resp.url()); appeared.set(true); }
                } catch (Exception ignored) {}
            });

            page.navigate(ARTICLE_PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            wd = page.evaluate("navigator.webdriver");
            System.out.println("[CDP] navigator.webdriver = " + wd + "（预期 false=真实Chrome）");
            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content, new Locator.PressSequentiallyOptions().setDelay(15));
            System.out.println("[CDP] 已写入，等待自动保存 15s ...");
            page.waitForTimeout(15000);

            boolean ind = false;
            try { if (page.locator("body").innerText().contains("已保存")) ind = true; } catch (Exception ignored) {}
            System.out.println("[CDP] 已保存指示器=" + ind);

            try { page.locator("button:has-text('暂存离开')").click(); } catch (Exception e) { System.out.println("[CDP][warn] 暂存离开:" + e.getMessage()); }
            page.waitForTimeout(5000);

            try {
                page.navigate("https://creator.xiaohongshu.com/new/home",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(3000);
                try { page.locator("text=草稿箱").first().click(); } catch (Exception ignored) {}
                for (int i = 0; i < 10; i++) {
                    try { if (page.content().contains(token)) { appeared.set(true); break; } } catch (Exception ignored) {}
                    page.waitForTimeout(2000);
                }
            } catch (Exception e) { System.out.println("[CDP][warn] 草稿箱检索:" + e.getMessage()); }
        } catch (Exception e) {
            System.out.println("[CDP][fatal] " + e);
        } finally {
            chrome.destroyForcibly();
        }

        System.out.println("\n==== CDP(" + (headed ? "headed" : "headless=new") + ") 结果 ====");
        System.out.println("navigator.webdriver = " + wd);
        System.out.println("保存类 POST(" + savePosts.size() + "):");
        savePosts.forEach(s -> System.out.println("  " + s));
        System.out.println("含标记响应(" + tokenResp.size() + "):");
        tokenResp.forEach(s -> System.out.println("  " + s));
        System.out.println("草稿真落库: " + (appeared.get() ? "是 ✓" : "否 ✗"));
        System.exit(appeared.get() ? 0 : 1);
    }
}
