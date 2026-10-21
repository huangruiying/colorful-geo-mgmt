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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 底层探针：打开编辑器 → 写内容 → dump 编辑器 innerHTML（确认 ProseMirror 是否真正拿到正文）
 * → 打印全部 xiaohongshu 域请求（含所有 method），看是否有保存接口被调用。
 */
public class XiaohongshuProbe {
    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[PROBE][error] 无登录态"); System.exit(2);
        }
        String storageState = login.getStorageState();
        String title = "[探针] 请公开发布测试 " + System.currentTimeMillis();
        String content = "探针正文内容测试一下保存是否触发。";

        Set<String> posts = new LinkedHashSet<>();
        List<String> allReq = new ArrayList<>();

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(List.of("--no-proxy-server",
                     "--disable-blink-features=AutomationControlled")));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            page.addInitScript("Object.defineProperty(navigator,'webdriver',{get:()=>false});");

            page.onRequest(req -> {
                String u = req.url();
                if (u.contains("xiaohongshu.com")) {
                    allReq.add(req.method() + " " + u);
                    if (req.method().equals("POST")) posts.add(u);
                }
            });

            page.navigate(ARTICLE_PUBLISH_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            System.out.println("[PROBE] 编辑器已打开");

            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content,
                    new Locator.PressSequentiallyOptions().setDelay(15));
            page.waitForTimeout(3000);

            // dump ProseMirror 内部 HTML，确认正文是否进入文档
            try {
                String html = body.innerHTML();
                System.out.println("[PROBE] ProseMirror innerHTML 长度=" + html.length());
                System.out.println("[PROBE] innerHTML 前 400 字: " + html.substring(0, Math.min(400, html.length())));
                System.out.println("[PROBE] 含正文片段=" + html.contains("探针正文"));
            } catch (Exception e) {
                System.out.println("[PROBE][warn] dump innerHTML 失败: " + e.getMessage());
            }

            // 点暂存离开后观察 8s
            try { page.locator("button:has-text('暂存离开')").click(); } catch (Exception ignored) {}
            page.waitForTimeout(8000);
        }

        System.out.println("\n==== 全部 POST 请求（xiaohongshu 域，" + posts.size() + " 个去重）====");
        posts.forEach(u -> System.out.println("  POST " + u));
        System.out.println("\n==== 全部请求 method+host 统计 ====");
        allReq.stream().map(s -> s.split(" ")[0] + " " + s.substring(s.indexOf("//")+2).split("/")[0])
                .distinct().forEach(s -> System.out.println("  " + s));
    }
}
