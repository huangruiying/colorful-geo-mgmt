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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 严谨验证：小红书自动保存到底成不成功。
 *   mode=headed   -> Playwright 有界面（headless=false）
 *   mode=headless -> Playwright 无界面（headless=true）
 *
 * 与旧探针的区别（旧探针的误判点）：
 *   1. 正确识别指示器：小红书文案是「自动保存于 HH:MM」/「保存中」，旧探针找「已保存」永远 false。
 *   2. 打印全部 xiaohongshu 域非 GET 请求（完整 URL），不再按关键词过滤，避免漏抓保存接口。
 *   3. 关键：阶段1 写完后**关闭浏览器**，阶段2 用**全新 Playwright 实例**只读打开草稿箱回读 token，
 *      彻底排除「本地 DOM 残留/同页回读」的假阳性。
 */
public class XiaohongshuHeadedVerify {
    private static final String PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String HOME_URL = "https://creator.xiaohongshu.com/new/home";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "headed";
        boolean headed = "headed".equals(mode);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = (headed ? "XHSHEADV" : "XHSHLV") + ts;
        String title = "[验证]自动保存 " + token;
        String content = "验证小红书自动保存是否真落库 " + token + "。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[V][error] 无登录态"); System.exit(2);
        }
        String ss = login.getStorageState();
        System.out.println("[V] 账号=" + login.getAccountName() + " 模式=" + mode + " 标记=" + token);

        List<String> nonGet = new CopyOnWriteArrayList<>();
        List<String> indicators = new CopyOnWriteArrayList<>();
        Object wd = "?";

        // ===== 阶段1：写内容（headed 或 headless）=====
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(!headed).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(ss))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            page.onRequest(req -> {
                String u = req.url();
                if (u.contains("xiaohongshu.com") && !req.method().equals("GET")) {
                    nonGet.add(req.method() + " " + u);
                }
            });

            page.navigate(PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            wd = page.evaluate("navigator.webdriver");
            System.out.println("[V] 编辑器已打开, navigator.webdriver=" + wd);

            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content, new Locator.PressSequentiallyOptions().setDelay(20));
            System.out.println("[V] 写入完成，轮询自动保存指示器（每 2s，共 15 次）...");
            for (int i = 0; i < 15; i++) {
                String t = "";
                try { t = page.locator("body").innerText(); } catch (Exception ignored) {}
                int idx = t.indexOf("自动保存");
                String snippet = idx >= 0
                        ? t.substring(idx, Math.min(t.length(), idx + 18)).replace("\n", " ").trim()
                        : (t.contains("保存中") ? "保存中" : "");
                // 只报关键状态，避免刷屏
                if (!snippet.isEmpty()) {
                    String last = indicators.isEmpty() ? "" : indicators.get(indicators.size() - 1);
                    if (!snippet.equals(last)) { indicators.add(snippet); System.out.println("  [" + i + "] " + snippet); }
                } else if (i == 14) {
                    System.out.println("  [14] 未检测到任何自动保存指示器");
                }
                page.waitForTimeout(2000);
            }
        } catch (Exception e) {
            System.out.println("[V][fatal] 阶段1失败: " + e);
        }
        System.out.println("[V] 阶段1结束（浏览器已关闭）");

        // ===== 阶段2：全新浏览器只读回读草稿箱 =====
        boolean found = false;
        String boxText = "";
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(ss))) {
            Page vp = ctx.newPage();
            vp.setDefaultTimeout(30000);
            vp.navigate(HOME_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            vp.waitForTimeout(6000);
            try { vp.locator("text=草稿箱").first().click(); } catch (Exception ignored) {}
            vp.waitForTimeout(4000);
            for (int i = 0; i < 15; i++) {
                try {
                    if (vp.content().contains(token) || vp.locator("body").innerText().contains(token)) {
                        found = true; boxText = vp.locator("body").innerText(); break;
                    }
                    boxText = vp.locator("body").innerText();
                } catch (Exception ignored) {}
                vp.waitForTimeout(2000);
            }
        } catch (Exception e) {
            System.out.println("[V][fatal] 阶段2失败: " + e);
        }

        System.out.println("\n==== 结果 (" + mode + ") ====");
        System.out.println("标记=" + token);
        System.out.println("navigator.webdriver=" + wd);
        System.out.println("自动保存指示器序列=" + indicators);
        System.out.println("全部 xiaohongshu 非GET请求(" + nonGet.size() + "):");
        nonGet.forEach(s -> System.out.println("  " + s));
        System.out.println("全新浏览器回读草稿箱找到 token: " + (found ? "是 ✓ 真落库" : "否 ✗"));
        String flat = boxText.replace("\n", " | ").replace("\r", "");
        System.out.println("草稿箱页面文本片段: " + flat.substring(0, Math.min(800, flat.length())));
        System.exit(found ? 0 : 1);
    }
}
