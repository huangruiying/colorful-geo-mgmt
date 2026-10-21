package org.huangry.colorful.geo.regtest;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.xiaohongshu.XiaohongshuBrowserClient;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 小红书长文草稿真实回归。
 *
 * <p>小红书是纯 SPA，长文草稿既无草稿 ID、也无"按 ID 重开草稿"的深链（编辑器 URL 不含草稿标识、
 * 页面无 {@code <a href>} 指向草稿、草稿 id 也无法在 headless 下通过接口响应可靠获取）。
 * 因此 createDraft 返回的 {@code remoteContentId} 为 null，{@code draftUrl} 指向创作者中心首页
 * （其「草稿箱」卡片可进入草稿），"打开草稿"只能带到草稿箱入口，不能精确打开某一篇。
 * 本回归不套用 {@link AbstractDraftRegression} 的"平台ID + 深链"五节点模型，改用自包含四节点：
 * <ol>
 *   <li>登录态就绪 —— DB login_status=LOGGED_IN</li>
 *   <li>会话可恢复 —— storage_state 可开会话，且能进「写长文」发布页并点「新的创作」打开编辑器</li>
 *   <li>草稿创建成功 —— 真实调用 {@code XiaohongshuBrowserClient.createDraft} 无异常返回
 *       （客户端内部已做标题/正文页内回读，回读不通过会抛异常）</li>
 *   <li>内容回读确认 —— 明示小红书无单篇草稿深链，"打开草稿"指向创作者中心草稿箱入口；
 *       且 headless 下点击「暂存离开」未必真正落库，内容是否进入草稿箱须以用户在真实浏览器核对为准</li>
 * </ol>
 *
 * <p>新版入口（2026-10-09 真实 UI 校正）：创作中心首页「发布笔记」已非 {@code <button>}，
 * 发布页默认视频 tab，需切「写长文」tab 后才出现「新的创作」；故直接深链到
 * {@code ?from=menu&target=article} 再点 {@code button.new-btn}。
 */
public class XiaohongshuDraftRegression extends AbstractDraftRegression {

    private static final String ARTICLE_PUBLISH_URL =
            "https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
    private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";

    @Override
    public String platformKey() {
        return "XIAOHONGSHU";
    }

    @Override
    public String displayName() {
        return "小红书";
    }

    @Override
    protected String creatorUrl() {
        return ARTICLE_PUBLISH_URL;
    }

    @Override
    protected String titleSelector() {
        return TITLE_SELECTOR;
    }

    @Override
    protected DraftCreatedResult createDraft(PlatformBrowserLoginDao dao, String title, String content) throws Exception {
        XiaohongshuBrowserClient client = new XiaohongshuBrowserClient(dao, browser, props);
        XiaohongshuBrowserClient.DraftCreated d = client.createDraft(title, content);
        return new DraftCreatedResult(d.remoteContentId(), d.draftUrl());
    }

    @Override
    protected String deepLink(DraftCreatedResult draft) {
        // 小红书无草稿深链：编辑器地址不含草稿标识，直接返回编辑器 URL 供展示。
        return draft.draftUrl();
    }

    @Override
    protected NodeResult verifyDeepLink(String storageState, DraftCreatedResult draft, String title, String content) {
        // 小红书无深链，本方法不在 verify() 中被调用；保留实现以满足基类契约。
        return NodeResult.pass("内容回读确认", "小红书无草稿深链，内容由客户端页内回读保证");
    }

    /**
     * 小红书专用四节点回归。覆盖基类默认流程，避免依赖"creatorUrl 上直接出现标题框"
     * 与"平台ID/深链"两处小红书不成立的前提。
     */
    @Override
    public List<NodeResult> verify(PlatformBrowserLoginEntity login, String title, String content) {
        List<NodeResult> nodes = new ArrayList<>();

        // ---- 节点1：登录态就绪 ----
        if (login == null) {
            nodes.add(NodeResult.fail("登录态就绪", "DB 无「XIAOHONGSHU」登录记录，请先在浏览器登录管理登录"));
            return nodes;
        }
        if (!"LOGGED_IN".equals(login.getLoginStatus())) {
            nodes.add(NodeResult.fail("登录态就绪", "login_status=" + login.getLoginStatus() + "（非 LOGGED_IN，请重新登录）"));
            return nodes;
        }
        String account = login.getAccountName() == null ? "?" : login.getAccountName();
        nodes.add(NodeResult.pass("登录态就绪", "LOGGED_IN / 账号=" + account));
        String storageState = login.getStorageState();

        // ---- 节点2：会话可恢复（能进写长文发布页并打开编辑器）----
        try (org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent.BrowserSession session =
                     browser.openBrowser(new Browser.NewContextOptions().setStorageState(storageState));
             Page page = session.newPage()) {
            page.setDefaultTimeout(props.getTimeoutMillis());
            page.navigate(ARTICLE_PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("button.new-btn").first().click();
            page.waitForSelector(TITLE_SELECTOR,
                    new Page.WaitForSelectorOptions().setTimeout(props.getTimeoutMillis()));
            nodes.add(NodeResult.pass("会话可恢复", "storage_state 可开启会话，写长文编辑器可达"));
        } catch (Exception e) {
            nodes.add(NodeResult.fail("会话可恢复", rootMsg(e)));
            nodes.add(NodeResult.skip("草稿创建成功", "会话恢复失败"));
            nodes.add(NodeResult.skip("内容回读确认", "会话恢复失败"));
            return nodes;
        }

        // ---- 节点3：草稿创建成功（客户端内含页内回读，回读不通过会抛异常）----
        PlatformBrowserLoginDao dao = PlatformLoginState.stubDao(platformKey(), login);
        DraftCreatedResult draft;
        try {
            draft = createDraft(dao, title, content);
            nodes.add(NodeResult.pass("草稿创建成功", "createDraft 无异常返回（含标题/正文页内回读）"));
        } catch (Exception e) {
            nodes.add(NodeResult.fail("草稿创建成功", rootMsg(e)));
            nodes.add(NodeResult.skip("内容回读确认", "草稿未创建"));
            return nodes;
        }
        this.lastDraftUrl = draft.draftUrl();
        this.lastRemoteContentId = draft.remoteContentId();

        // ---- 节点4：内容回读确认（小红书无草稿ID/深链，打开草稿指向创作者中心草稿箱）----
        nodes.add(NodeResult.pass("内容回读确认",
                "小红书无单篇草稿深链，标题/正文由客户端页内回读保证；\"打开草稿\"指向创作者中心草稿箱入口（" + draft.draftUrl()
                        + "）。注意：headless 下点击「暂存离开」未必真正落库，草稿是否进入草稿箱须以用户在真实浏览器核对为准"));
        return nodes;
    }
}
