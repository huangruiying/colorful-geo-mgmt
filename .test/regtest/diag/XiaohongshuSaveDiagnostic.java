package org.huangry.colorful.geo.regtest.diag;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.huangry.colorful.geo.regtest.PlatformLoginState;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 小红书草稿"是否真落库"诊断。
 *
 * <p>复现生产路径：用 DB 里的真实登录态开 headless 会话 → 进写长文编辑器 → 写唯一标题/正文
 * → 点「暂存离开」→ 然后去创作者中心草稿箱/草稿列表接口里查这篇唯一标题是否真的存在。</p>
 *
 * <p>判据不是"页内还显示着"（那永远为真），而是"小红书服务端草稿数据里能否查到这篇"。
 * 这是判定草稿是否真被创建的唯一可靠标准。</p>
 *
 * <p>A/B：mode=repro 复现现状（仅 --no-proxy-server，短等待）；mode=fix 测试修复
 * （加防后台节流参数 + 长等待让自动保存真正发出）。</p>
 */
public class XiaohongshuSaveDiagnostic {

    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0] : "fix";
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSDIAG" + ts; // 唯一标记，用于去草稿列表接口/页面里检索
        String title = "[诊断]" + token + " 请勿公开发布";
        String content = "这是小红书草稿落库诊断的示例正文，" + token + "，用于确认草稿是否真进入草稿箱。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[DIAG][error] DB 无 XIAOHONGSHU 登录态或状态非 LOGGED_IN");
            System.exit(2);
        }
        String storageState = login.getStorageState();
        System.out.println("[DIAG] mode=" + mode + " 账号=" + login.getAccountName() + " 唯一标记=" + token);

        List<String> saveHits = new CopyOnWriteArrayList<>();
        List<String> titleFoundInBody = new CopyOnWriteArrayList<>();
        AtomicBoolean draftAppeared = new AtomicBoolean(false);
        AtomicReference<String> noteIdRef = new AtomicReference<>();

        List<String> launchArgs = new ArrayList<>();
        launchArgs.add("--no-proxy-server");
        if ("fix".equals(mode)) {
            launchArgs.add("--disable-background-timer-throttling");
            launchArgs.add("--disable-backgrounding-occluded-windows");
            launchArgs.add("--disable-renderer-backgrounding");
        }

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(launchArgs));
             BrowserContext ctx = browser.newContext(
                     new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);

            // 网络捕获：记录所有 POST，并在响应体里检索唯一标记
            page.onRequest(req -> {
                String u = req.url();
                if ("POST".equalsIgnoreCase(req.method())
                        && (u.contains("save") || u.contains("note") || u.contains("draft")
                        || u.contains("editing") || u.contains("publish"))) {
                    saveHits.add(req.method() + " " + u);
                }
            });
            page.onResponse(resp -> {
                String u = resp.url();
                try {
                    String body = resp.text();
                    if (body != null && body.contains(token)) {
                        titleFoundInBody.add("RESP " + resp.status() + " " + u + " (含标记)");
                        draftAppeared.set(true);
                        // 尝试从 URL 或 body 抓 note id
                        int i = u.indexOf("note_id=");
                        if (i >= 0) noteIdRef.set(u.substring(i + 8, Math.min(i + 40, u.length())));
                    }
                } catch (Exception ignored) {
                    // 非文本/二进制响应忽略
                }
            });

            // 1. 进写长文编辑器
            page.navigate(ARTICLE_PUBLISH_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.locator(TITLE_SELECTOR).waitFor();
            page.locator(BODY_SELECTOR).first().waitFor();
            System.out.println("[DIAG] 编辑器已打开");

            // 2. 写唯一标题/正文
            page.locator(TITLE_SELECTOR).fill(title);
            page.locator(BODY_SELECTOR).first().click();
            page.keyboard().insertText(content);
            System.out.println("[DIAG] 已写入标题/正文");

            // 3. 等待自动保存（fix 模式等更久，让 debounce 定时器真正发出）
            int waitMs = "fix".equals(mode) ? 15000 : 3000;
            System.out.println("[DIAG] 等待自动保存 " + waitMs + "ms ...");
            page.waitForTimeout(waitMs);

            // 4. 点「暂存离开」
            try {
                page.locator("button:has-text('暂存离开')").click();
                System.out.println("[DIAG] 已点「暂存离开」");
            } catch (Exception e) {
                System.out.println("[DIAG][warn] 点「暂存离开」失败: " + e.getMessage());
            }
            page.waitForTimeout("fix".equals(mode) ? 5000 : 2000);

            // 5. 去创作者中心草稿箱，轮询检索唯一标记（页面 + 已捕获响应体）
            System.out.println("[DIAG] 前往创作者中心草稿箱检索 ...");
            try {
                page.navigate("https://creator.xiaohongshu.com/new/home",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                // 等草稿箱卡片并尝试点击进入草稿列表
                page.waitForTimeout(3000);
                try {
                    page.locator("text=草稿箱").first().click();
                    System.out.println("[DIAG] 已点击草稿箱");
                } catch (Exception e) {
                    System.out.println("[DIAG][warn] 未找到/未点到草稿箱卡片: " + e.getMessage());
                }
                // 轮询页面内容
                for (int i = 0; i < 10; i++) {
                    try {
                        String html = page.content();
                        if (html.contains(token)) {
                            draftAppeared.set(true);
                            System.out.println("[DIAG] 第" + (i + 1) + "次轮询：页面内容含唯一标记 ✓");
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                    page.waitForTimeout(2000);
                }
            } catch (Exception e) {
                System.out.println("[DIAG][warn] 草稿箱检索异常: " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("==== 诊断结果 (" + mode + ") ====");
        System.out.println("保存类请求命中(" + saveHits.size() + "):");
        saveHits.forEach(s -> System.out.println("  " + s));
        System.out.println("含唯一标记的响应(" + titleFoundInBody.size() + "):");
        titleFoundInBody.forEach(s -> System.out.println("  " + s));
        System.out.println("note id: " + (noteIdRef.get() == null ? "(未捕获)" : noteIdRef.get()));
        System.out.println("草稿是否真落库: " + (draftAppeared.get() ? "是 ✓ (服务端能查到这篇)" : "否 ✗ (伪成功：页内显示但服务端无此草稿)"));
        System.exit(draftAppeared.get() ? 0 : 1);
    }
}
