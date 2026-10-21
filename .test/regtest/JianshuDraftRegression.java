package org.huangry.colorful.geo.regtest;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.jianshu.JianshuBrowserClient;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;

/**
 * 简书真实回归。深链即 createDraft 返回的 draftUrl（已含 notes/{id}）。
 * 第 5 节点强校验：用深链在已登录会话重新打开草稿，回读标题与正文须与预期一致
 * （对应此前"打开草稿看不到内容"的回归点）。
 */
public class JianshuDraftRegression extends AbstractDraftRegression {

    private static final String BODY_SELECTOR = "textarea#arthur-editor";

    @Override
    public String platformKey() {
        return "JIANSHU";
    }

    @Override
    public String displayName() {
        return "简书";
    }

    @Override
    protected String creatorUrl() {
        return "https://www.jianshu.com/writer";
    }

    @Override
    protected String titleSelector() {
        return "input:not([placeholder=\"请输入文集名...\"])";
    }

    @Override
    protected DraftCreatedResult createDraft(PlatformBrowserLoginDao dao, String title, String content) throws Exception {
        JianshuBrowserClient client = new JianshuBrowserClient(dao, browser, props);
        JianshuBrowserClient.DraftCreated d = client.createDraft(title, content);
        return new DraftCreatedResult(d.remoteContentId(), d.draftUrl());
    }

    @Override
    protected String deepLink(DraftCreatedResult draft) {
        // 简书 createDraft 返回的 draftUrl 已是写作页深链（含 notes/{id}）
        return draft.draftUrl();
    }

    @Override
    protected NodeResult verifyDeepLink(String storageState, DraftCreatedResult draft, String title, String content) throws Exception {
        try (PlaywrightBrowserComponent.BrowserSession session = browser.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState));
             Page page = session.newPage()) {
            page.setDefaultTimeout(props.getTimeoutMillis());
            // 简书为 SPA，深链需 reload 让服务端拉取内容，否则只读本地 DOM 造成假阳性
            page.navigate(deepLink(draft), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForSelector(titleSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(props.getTimeoutMillis()));
            String js = "([t]) => {"
                    + " const el = document.querySelector('input:not([placeholder=\"请输入文集名...\"])');"
                    + " const b = document.querySelector('textarea#arthur-editor');"
                    + " return !!el && el.value === t && !!b && b.value.trim().length > 0;"
                    + " }";
            page.waitForFunction(js, new Object[]{title},
                    new Page.WaitForFunctionOptions().setTimeout(props.getTimeoutMillis()));
            String savedTitle = page.locator(titleSelector()).first().inputValue();
            String savedBody = page.locator(BODY_SELECTOR).first().inputValue().stripTrailing();
            if (title.equals(savedTitle) && content.stripTrailing().equals(savedBody)) {
                return NodeResult.pass("深链内容可见", "标题与正文回读一致");
            }
            return NodeResult.fail("深链内容可见",
                    "标题或正文不一致（已载入标题=" + savedTitle + "，可能服务端未落库）");
        }
    }
}
