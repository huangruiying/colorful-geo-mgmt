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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 定位小红书网页版草稿箱入口，并查服务端返回里是否含历史探针 token（证明是否真落库）。
 */
public class XiaohongshuDraftBoxFind {
    private static final String PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";

    public static void main(String[] args) throws Exception {
        PlatformBrowserLoginEntity login = PlatformLoginState.load("XIAOHONGSHU");
        if (login == null || !"LOGGED_IN".equals(login.getLoginStatus())) {
            System.out.println("[FIND][error] 无登录态"); System.exit(2);
        }
        String ss = login.getStorageState();
        System.out.println("[FIND] 账号=" + login.getAccountName());

        List<String> respWithToken = new CopyOnWriteArrayList<>();

        try (Playwright pw = Playwright.create();
             Browser b = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setArgs(List.of("--no-proxy-server")));
             BrowserContext ctx = b.newContext(new Browser.NewContextOptions().setStorageState(ss))) {
            Page page = ctx.newPage();
            page.setDefaultTimeout(30000);
            page.onResponse(resp -> {
                try {
                    String bd = resp.text();
                    if (bd != null && (bd.contains("XHSHEAD") || bd.contains("XHSSV") || bd.contains("XHSCDP") || bd.contains("保存验证"))) {
                        respWithToken.add(resp.status() + " " + resp.url() + "\n      => " + bd.substring(0, Math.min(800, bd.length())));
                    }
                } catch (Exception ignored) {}
            });

            page.navigate(PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(10000);
            System.out.println("[FIND] 当前 URL=" + page.url());

            Object drafts = page.evaluate("() => Array.from(document.querySelectorAll('*'))"
                    + ".filter(e=>{const t=(e.textContent||'').trim(); return t.length<14 && /草稿/.test(t);})"
                    + ".map(e=>e.tagName+'.'+(typeof e.className==='string'?e.className:'')+' :: '+e.textContent.trim()).slice(0,30)");
            System.out.println("[FIND] 含'草稿'元素: " + drafts);

            for (String sel : List.of("text=草稿箱", "text=草稿")) {
                try {
                    page.locator(sel).first().click(new Locator.ClickOptions().setTimeout(5000));
                    System.out.println("[FIND] 点击 [" + sel + "] 成功");
                    page.waitForTimeout(6000);
                    break;
                } catch (Exception e) {
                    System.out.println("[FIND] 点击 [" + sel + "] 失败: " + e.getMessage().split("\n")[0]);
                }
            }

            String t = page.locator("body").innerText();
            boolean hasHist = t.contains("XHSHEAD") || t.contains("XHSSV") || t.contains("XHSCDP");
            System.out.println("[FIND] 跳转后 URL=" + page.url());
            System.out.println("[FIND] 页面文本含历史 token=" + hasHist);
            System.out.println("[FIND] 页面文本片段: " + t.substring(0, Math.min(1500, t.length())).replace("\n", " | "));
            System.out.println("[FIND] 服务端响应含 token 的(" + respWithToken.size() + "):");
            respWithToken.forEach(s -> System.out.println("   " + s));
        }
    }
}
