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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 决定性实验：有头模式（headless=false，真实渲染窗口，无 headless 指纹）能否让小红书编辑器真正保存草稿。
 */
public class XiaohongshuHeadedProbe {
    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSHEAD" + ts;
        String title = "[有头探针]" + token;
        String content = "有头模式保存测试，" + token + "。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[HEADED][error] 无登录态"); System.exit(2);
        }
        String storageState = login.getStorageState();
        System.out.println("[HEADED] 账号=" + login.getAccountName() + " 标记=" + token);

        List<String> savePosts = new CopyOnWriteArrayList<>();
        List<String> tokenResp = new CopyOnWriteArrayList<>();
        AtomicBoolean appeared = new AtomicBoolean(false);

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(false).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(storageState))) {
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

            page.navigate(ARTICLE_PUBLISH_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            System.out.println("[HEADED] 编辑器已打开");
            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content, new Locator.PressSequentiallyOptions().setDelay(15));
            System.out.println("[HEADED] 已写入，等待自动保存 15s ...");
            page.waitForTimeout(15000);

            boolean ind = false;
            try { if (page.locator("body").innerText().contains("已保存")) ind = true; } catch (Exception ignored) {}
            System.out.println("[HEADED] 已保存指示器=" + ind);

            try { page.locator("button:has-text('暂存离开')").click(); } catch (Exception e) { System.out.println("[HEADED][warn] 暂存离开失败:" + e.getMessage()); }
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
            } catch (Exception e) { System.out.println("[HEADED][warn] 草稿箱检索:" + e.getMessage()); }
        } catch (Exception e) {
            System.out.println("[HEADED][fatal] 有头模式启动失败（可能无显示器/display）: " + e.getMessage());
            System.exit(3);
        }

        System.out.println("\n==== 有头模式结果 ====");
        System.out.println("保存类 POST(" + savePosts.size() + "):");
        savePosts.forEach(s -> System.out.println("  " + s));
        System.out.println("含标记响应(" + tokenResp.size() + "):");
        tokenResp.forEach(s -> System.out.println("  " + s));
        System.out.println("草稿真落库: " + (appeared.get() ? "是 ✓" : "否 ✗"));
        System.exit(appeared.get() ? 0 : 1);
    }
}
