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

/**
 * 闭环验证：小红书网页草稿是否 = 浏览器本地存储。
 *   同一浏览器实例内：创建长文草稿 → 暂存离开 → 回发布页打开草稿箱，看是否能看见本篇。
 *   同时 dump localStorage / IndexedDB 中是否直接含 token。
 * 若同实例可见、跨实例不可见 → 铁证「草稿存浏览器本地，非服务端」。
 */
public class XiaohongshuLocalDraftVerify {
    private static final String PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "headless";
        boolean headed = "headed".equals(mode);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        String token = "XHSLOCAL" + ts;
        String title = "[本地草稿]" + token;
        String content = "本地草稿验证正文 " + token + "。";

        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[LOCAL][error] 无登录态"); System.exit(2);
        }
        String ss = login.getStorageState();
        System.out.println("[LOCAL] 账号=" + login.getAccountName() + " mode=" + mode + " token=" + token);

        try (Playwright pw = Playwright.create();
             Browser b = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(!headed).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = b.newContext(new Browser.NewContextOptions().setStorageState(ss))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);

            page.navigate(PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(4000);

            boolean opened = false;
            for (int i = 0; i < 3 && !opened; i++) {
                try {
                    page.locator("button.new-btn").first().click(new Locator.ClickOptions().setTimeout(9000));
                    opened = true;
                } catch (Exception e) {
                    System.out.println("[LOCAL] new-btn 重试 " + (i + 1) + ": " + e.getMessage().split("\n")[0]);
                    page.waitForTimeout(3000);
                }
            }
            page.locator(TITLE_SELECTOR).waitFor();
            Locator body = page.locator(BODY_SELECTOR).first();
            body.waitFor();
            page.locator(TITLE_SELECTOR).fill(title);
            body.click();
            body.pressSequentially(content, new Locator.PressSequentiallyOptions().setDelay(20));
            try { page.bringToFront(); } catch (Exception ignored) {}
            System.out.println("[LOCAL] 写入完成，等 10s 让本地自动保存");
            page.waitForTimeout(10000);

            dumpLocal(page, token, "写完时");

            try { page.locator("button:has-text('暂存离开')").click(); System.out.println("[LOCAL] 已点暂存离开"); }
            catch (Exception e) { System.out.println("[LOCAL][warn] 暂存离开: " + e.getMessage().split("\n")[0]); }
            page.waitForTimeout(6000);
            System.out.println("[LOCAL] 暂存后 URL=" + page.url());

            // 回发布页，点草稿箱
            page.navigate(PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(6000);
            try { page.locator("text=草稿箱").first().click(); } catch (Exception ignored) {}
            page.waitForTimeout(6000);
            String t = page.locator("body").innerText();
            System.out.println("[LOCAL] >>> 同实例草稿箱文本含 token=" + t.contains(token));
            int di = t.indexOf("草稿箱");
            System.out.println("[LOCAL] 草稿箱附近文本: " + t.substring(Math.max(0, di - 20), Math.min(t.length(), di + 300)).replace("\n", " | "));

            dumpLocal(page, token, "草稿箱页");
        }
    }

    private static void dumpLocal(Page page, String token, String tag) {
        try {
            Object ls = page.evaluate("(tk) => { let out=[]; for(let i=0;i<localStorage.length;i++){let k=localStorage.key(i); let v=localStorage.getItem(k)||''; if(v.includes(tk)) out.push(k+' => '+v.slice(0,200));} return out.length?out:('localStorage 未含 token，keys='+Object.keys(localStorage).slice(0,25).join(',')); }", token);
            System.out.println("[LOCAL] localStorage(" + tag + "): " + ls);
        } catch (Exception e) { System.out.println("[LOCAL] localStorage(" + tag + ") 失败: " + e.getMessage().split("\n")[0]); }
        try {
            Object idb = page.evaluate("() => (window.indexedDB && window.indexedDB.databases) ? 'idb-databases 可用' : 'n/a'");
            System.out.println("[LOCAL] IndexedDB(" + tag + "): " + idb);
        } catch (Exception ignored) {}
    }
}
