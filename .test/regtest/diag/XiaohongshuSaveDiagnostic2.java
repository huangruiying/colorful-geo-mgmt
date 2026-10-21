package org.huangry.colorful.geo.regtest.diag;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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
 * 小红书草稿落库诊断 v2：抓全部 xiaohongshu 域请求，区分 repro / fix / mask 三种启动方式。
 * - repro: 仅 --no-proxy-server（生产等价）
 * - fix:   + 防后台节流
 * - mask:  + 屏蔽 navigator.webdriver 检测（--disable-blink-features=AutomationControlled + initScript）
 * 并在写完后轮询编辑器内"已保存/已自动保存"指示器，判断自动保存是否真正触发。
 */
public class XiaohongshuSaveDiagnostic2 {

    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0] : "mask";
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSDIAG" + ts;
        String title = "[诊断]" + token + " 请勿公开发布";
        String content = "这是小红书草稿落库诊断的示例正文，" + token + "，用于确认草稿是否真进入草稿箱。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[DIAG2][error] DB 无 XIAOHONGSHU 登录态");
            System.exit(2);
        }
        String storageState = login.getStorageState();
        System.out.println("[DIAG2] mode=" + mode + " 账号=" + login.getAccountName() + " 标记=" + token);

        List<String> allReq = new CopyOnWriteArrayList<>();
        List<String> saveLike = new CopyOnWriteArrayList<>();
        List<String> tokenResp = new CopyOnWriteArrayList<>();
        AtomicBoolean draftAppeared = new AtomicBoolean(false);

        List<String> launchArgs = new ArrayList<>();
        launchArgs.add("--no-proxy-server");
        if (!"repro".equals(mode)) {
            launchArgs.add("--disable-background-timer-throttling");
            launchArgs.add("--disable-backgrounding-occluded-windows");
            launchArgs.add("--disable-renderer-backgrounding");
        }
        if ("mask".equals(mode)) {
            launchArgs.add("--disable-blink-features=AutomationControlled");
        }

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(launchArgs));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            if ("mask".equals(mode)) {
                page.addInitScript("Object.defineProperty(navigator,'webdriver',{get:()=>false});");
            }

            page.onRequest(req -> {
                String u = req.url();
                if (u.contains("xiaohongshu.com") || u.contains("xhscdn.com")) {
                    allReq.add(req.method() + " " + u);
                    if (req.method().equals("POST")) {
                        String lu = u.toLowerCase();
                        if (lu.contains("save") || lu.contains("draft") || lu.contains("note")
                                || lu.contains("editing") || lu.contains("publish")) {
                            saveLike.add(req.method() + " " + u);
                        }
                    }
                }
            });
            page.onResponse(resp -> {
                String u = resp.url();
                try {
                    String body = resp.text();
                    if (body != null && body.contains(token)) {
                        tokenResp.add("RESP " + resp.status() + " " + u);
                        draftAppeared.set(true);
                    }
                } catch (Exception ignored) {
                }
            });

            page.navigate(ARTICLE_PUBLISH_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            page.locator(BODY_SELECTOR).first().waitFor();
            System.out.println("[DIAG2] 编辑器已打开");

            page.locator(TITLE_SELECTOR).fill(title);
            // 正文为 ProseMirror/TipTap：逐字符真实按键，触发其 beforeinput 变更检测
            // （insertText 不会触发，导致编辑器内部文档状态为空、不保存）。
            com.microsoft.playwright.Locator body = page.locator(BODY_SELECTOR).first();
            body.click();
            body.pressSequentially(content,
                    new com.microsoft.playwright.Locator.PressSequentiallyOptions().setDelay(15));
            System.out.println("[DIAG2] 已写入标题/正文（pressSequentially）");

            // 轮询编辑器内"已保存/已自动保存"指示器（判断自动保存是否触发）
            boolean autoSaved = false;
            for (int i = 0; i < 12; i++) {
                try {
                    String txt = page.locator("body").innerText();
                    if (txt.contains("已保存") || txt.contains("已自动保存") || txt.contains("保存成功")) {
                        autoSaved = true;
                        System.out.println("[DIAG2] 第" + (i + 1) + "次轮询：检测到保存指示器 ✓");
                        break;
                    }
                } catch (Exception ignored) {
                }
                page.waitForTimeout(1500);
            }
            System.out.println("[DIAG2] 自动保存指示器出现=" + autoSaved);

            try {
                page.locator("button:has-text('暂存离开')").click();
                System.out.println("[DIAG2] 已点「暂存离开」");
            } catch (Exception e) {
                System.out.println("[DIAG2][warn] 点「暂存离开」失败: " + e.getMessage());
            }
            page.waitForTimeout(5000);

            // 草稿箱检索
            try {
                page.navigate("https://creator.xiaohongshu.com/new/home",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(3000);
                try {
                    page.locator("text=草稿箱").first().click();
                } catch (Exception ignored) {
                }
                for (int i = 0; i < 10; i++) {
                    try {
                        if (page.content().contains(token)) {
                            draftAppeared.set(true);
                            System.out.println("[DIAG2] 第" + (i + 1) + "次轮询页面含标记 ✓");
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                    page.waitForTimeout(2000);
                }
            } catch (Exception e) {
                System.out.println("[DIAG2][warn] 草稿箱检索异常: " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("==== 诊断结果 v2 (" + mode + ") ====");
        System.out.println("xiaohongshu 域请求总数=" + allReq.size());
        System.out.println("保存类 POST(" + saveLike.size() + "):");
        saveLike.forEach(s -> System.out.println("  " + s));
        System.out.println("含标记的响应(" + tokenResp.size() + "):");
        tokenResp.forEach(s -> System.out.println("  " + s));
        System.out.println("草稿是否真落库: " + (draftAppeared.get() ? "是 ✓" : "否 ✗"));
        System.exit(draftAppeared.get() ? 0 : 1);
    }
}
