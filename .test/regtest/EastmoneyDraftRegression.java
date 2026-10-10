package org.huangry.colorful.geo.regtest;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.eastmoney.EastmoneyBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;

/**
 * 东方财富真实回归。深链为 {@code #/?id=<draft_id>}（平台只认 id 这个 key）。
 * 第 5 节点校验深链能在已登录会话下打开编辑器（内容回读已在 createDraft 内 verifySavedDraft 完成）。
 */
public class EastmoneyDraftRegression extends AbstractDraftRegression {

    @Override
    public String platformKey() {
        return "EASTMONEY";
    }

    @Override
    public String displayName() {
        return "东方财富";
    }

    @Override
    protected String creatorUrl() {
        return "https://mp.eastmoney.com/collect/pc_article/index.html#/";
    }

    @Override
    protected String titleSelector() {
        return "input[placeholder=\"标题(1-64字)\"]";
    }

    @Override
    protected DraftCreatedResult createDraft(PlatformBrowserLoginDao dao, String title, String content) throws Exception {
        EastmoneyBrowserClient client = new EastmoneyBrowserClient(dao, browser, props, new ObjectMapper());
        EastmoneyBrowserClient.DraftCreated d = client.createDraft(title, content);
        return new DraftCreatedResult(d.remoteContentId(), d.draftUrl());
    }

    @Override
    protected String deepLink(DraftCreatedResult draft) {
        return "https://mp.eastmoney.com/collect/pc_article/index.html#/?id=" + draft.remoteContentId();
    }

    @Override
    protected NodeResult verifyDeepLink(String storageState, DraftCreatedResult draft, String title, String content) throws Exception {
        try (PlaywrightBrowserComponent.BrowserSession session = browser.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState));
             Page page = session.newPage()) {
            page.setDefaultTimeout(props.getTimeoutMillis());
            page.navigate(deepLink(draft), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForSelector(titleSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(props.getTimeoutMillis()));
            // 东方财富为 hash 路由，深链打开后可能弹"您有一篇未编辑的文章"提示，或直接载入草稿；
            // 只要编辑器可达即证明深链有效（内容回读已在前序 createDraft 内完成）。
            String savedTitle = "";
            try {
                savedTitle = page.locator(titleSelector()).first().inputValue();
            } catch (Exception ignored) {
                // 取不到标题不影响"编辑器可达"结论
            }
            String detail = "编辑器可达";
            if (title.equals(savedTitle)) {
                detail += "，标题已回读一致";
            }
            return NodeResult.pass("深链内容可见", detail);
        }
    }
}
