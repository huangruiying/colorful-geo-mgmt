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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存落库验证 v3：
 *   1. 监听 WebSocket 帧（旧探针只监听 HTTP request，保存若走 WS 必漏）。
 *   2. 点「暂存离开」后记录跳转 URL + 检查页面是否含 token（草稿列表很可能就在跳转后的页面）。
 *   3. 阶段2：全新浏览器 dump 首页所有链接，定位草稿箱入口。
 */
public class XiaohongshuSaveVerify {
    private static final String PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String HOME_URL = "https://creator.xiaohongshu.com/new/home";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "headed-active";
        boolean headed = !"headless".equals(mode);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSSV" + ts;
        String title = "[保存验证]" + token;
        String content = "保存验证正文 " + token + "。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[SV][error] 无登录态"); System.exit(2);
        }
        String ss = login.getStorageState();
        System.out.println("[SV] 账号=" + login.getAccountName() + " mode=" + mode + " token=" + token);

        List<String> nonGet = new CopyOnWriteArrayList<>();
        List<String> bodyHit = new CopyOnWriteArrayList<>();
        List<String> wsHit = new CopyOnWriteArrayList<>();
        List<String> indicators = new CopyOnWriteArrayList<>();
        Pattern savePattern = Pattern.compile("自动保存于\\s*\\d{1,2}:\\d{2}");

        List<String> launchArgs = new java.util.ArrayList<>(List.of("--no-proxy-server"));
        if (headed) {
            launchArgs.add("--disable-background-timer-throttling");
            launchArgs.add("--disable-renderer-backgrounding");
            launchArgs.add("--disable-backgrounding-occluded-windows");
        }

        String afterLeaveUrl = "";
        boolean afterLeaveHasToken = false;
        String afterLeaveSnippet = "";

        // ===== 阶段1 =====
        try (Playwright pw = Playwright.create();
             Browser b = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(!headed).setArgs(launchArgs));
             BrowserContext ctx = b.newContext(new Browser.NewContextOptions().setStorageState(ss))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            page.onRequest(req -> {
                String u = req.url();
                if (u.contains("xiaohongshu.com") && !req.method().equals("GET")) nonGet.add(req.method() + " " + u);
                try {
                    String pd = req.postData();
                    if (pd != null && (pd.contains(token) || pd.contains("保存验证"))) {
                        bodyHit.add(req.method() + " " + req.url() + "\n        BODY=" + pd.substring(0, Math.min(500, pd.length())));
                    }
                } catch (Exception ignored) {}
            });
            page.onWebSocket(ws -> {
                try {
                    ws.onFrameSent(f -> {
                        try { String t = f.text(); if (t != null && (t.contains(token) || t.contains("保存验证"))) wsHit.add("SENT " + t.substring(0, Math.min(300, t.length()))); } catch (Exception ignored) {}
                    });
                    ws.onFrameReceived(f -> {
                        try { String t = f.text(); if (t != null && t.contains(token)) wsHit.add("RECV " + t.substring(0, Math.min(300, t.length()))); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            });

            page.navigate(PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            System.out.println("[SV] 编辑器已打开, webdriver=" + page.evaluate("navigator.webdriver"));
            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content, new Locator.PressSequentiallyOptions().setDelay(20));
            try { page.bringToFront(); } catch (Exception ignored) {}

            System.out.println("[SV] 写入完成，轮询 45s 检测「自动保存于」...");
            for (int i = 0; i < 15; i++) {
                String t = "";
                try { t = page.locator("body").innerText(); } catch (Exception ignored) {}
                Matcher m = savePattern.matcher(t);
                if (m.find()) {
                    if (!indicators.contains(m.group())) { indicators.add(m.group()); System.out.println("  [" + i + "] " + m.group()); }
                }
                page.waitForTimeout(3000);
            }

            System.out.println("[SV] 点「暂存离开」...");
            try { page.locator("button:has-text('暂存离开')").click(); System.out.println("[SV] 已点"); }
            catch (Exception e) { System.out.println("[SV][warn] " + e.getMessage()); }
            page.waitForTimeout(7000);

            try {
                afterLeaveUrl = page.url();
                String t2 = page.locator("body").innerText();
                afterLeaveHasToken = t2.contains(token) || page.content().contains(token);
                afterLeaveSnippet = t2.substring(0, Math.min(1200, t2.length())).replace("\n", " | ");
            } catch (Exception ignored) {}
            System.out.println("[SV] 暂存离开后 URL=" + afterLeaveUrl);
            System.out.println("[SV] 暂存离开后页面含 token=" + afterLeaveHasToken);
            System.out.println("[SV] 阶段1结束");
        } catch (Exception e) {
            System.out.println("[SV][fatal] 阶段1: " + e);
        }

        // ===== 阶段2：全新浏览器，dump 首页链接 + 找草稿箱入口 =====
        List<String> homeLinks = new CopyOnWriteArrayList<>();
        try (Playwright pw = Playwright.create();
             Browser b = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = b.newContext(new Browser.NewContextOptions().setStorageState(ss))) {
            Page vp = ctx.newPage();
            vp.setDefaultTimeout(30000);
            vp.navigate(HOME_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            vp.waitForTimeout(10000);
            try {
                Object links = vp.evaluate("() => Array.from(document.querySelectorAll('a')).map(a => (a.textContent||'').trim()+' -> '+a.href).filter(s=>s.length>4)");
                homeLinks.add(String.valueOf(links));
            } catch (Exception ignored) {}
            try {
                Object menu = vp.evaluate("() => Array.from(document.querySelectorAll('*')).filter(e=>e.children.length===0 && /草稿/.test(e.textContent||'')).map(e=>(e.textContent||'').trim()).slice(0,20)");
                homeLinks.add("含'草稿'文本元素: " + menu);
            } catch (Exception ignored) {}
            // 直接尝试草稿箱候选 URL
            for (String cand : List.of("https://creator.xiaohongshu.com/new/note-manager",
                    "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article")) {
                try {
                    vp.navigate(cand, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    vp.waitForTimeout(5000);
                    String t = vp.locator("body").innerText();
                    homeLinks.add("候选 " + cand + " 含token=" + t.contains(token) + " 含'草稿'=" + t.contains("草稿"));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("[SV][fatal] 阶段2: " + e);
        }

        System.out.println("\n==== 结果 (" + mode + ") ====");
        System.out.println("标记=" + token);
        System.out.println("「自动保存于」指示器: " + (indicators.isEmpty() ? "从未出现" : indicators.toString()));
        System.out.println("HTTP 请求体含本次内容: " + (bodyHit.isEmpty() ? "无" : bodyHit.size() + " 个"));
        bodyHit.forEach(s -> System.out.println("    " + s));
        System.out.println("WebSocket 帧含本次内容: " + (wsHit.isEmpty() ? "无" : wsHit.size() + " 个"));
        wsHit.forEach(s -> System.out.println("    " + s));
        System.out.println("暂存离开后 URL: " + afterLeaveUrl + "  含Token=" + afterLeaveHasToken);
        System.out.println("暂存离开后页面片段: " + afterLeaveSnippet);
        System.out.println("\n阶段2 页面链接/草稿入口探测:");
        homeLinks.forEach(s -> System.out.println("  " + s));
        System.out.println("\n全部非 GET 请求(" + nonGet.size() + "):");
        nonGet.stream().distinct().forEach(s -> System.out.println("  " + s));
    }
}
