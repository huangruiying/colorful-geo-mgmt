package org.huangry.colorful.geo.regtest;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 草稿类平台回归的通用骨架：把"走通"拆成 5 个可独立判定成败的节点，
 * 子类只需提供构造客户端、深链、以及深链内容回读校验三个钩子。
 *
 * <p>节点含义（任一节点失败都不伪造后续成功，缺前置条件则后续节点 SKIP）：
 * <ol>
 *   <li>登录态就绪 —— DB 有该平台记录且 login_status=LOGGED_IN</li>
 *   <li>会话可恢复 —— 用 storage_state 能开启隔离浏览器会话并到达编辑器（剔除"登录过期被踢到登录墙"）</li>
 *   <li>草稿创建成功 —— 真实调用客户端 createDraft 无异常返回</li>
 *   <li>平台ID已捕获 —— remoteContentId 非空（东方财富=SaveDraft 的 draft_id；简书=note id）</li>
 *   <li>深链内容可见 —— 用深链在已登录会话里重新打开草稿，回读标题/正文一致（简书强校验，东方财富校验编辑器可达）</li>
 * </ol>
 */
public abstract class AbstractDraftRegression implements BrowserRegression {

    protected final PlaywrightBrowserComponent browser = new PlaywrightBrowserComponent();
    protected final PublicationBrowserProperties props = new PublicationBrowserProperties();

    /** 最近一次 createDraft 的产物，供 Runner 汇总"本次创建的真实草稿"清单（便于清理）。 */
    protected String lastDraftUrl;
    protected String lastRemoteContentId;

    /** 创作者页基础 URL（会话恢复节点在此导航并等待编辑器）。 */
    protected abstract String creatorUrl();

    /** 标题输入框选择器（编辑器可达性的判定锚点）。 */
    protected abstract String titleSelector();

    /** 用真实客户端创建草稿，返回统一产物。 */
    protected abstract DraftCreatedResult createDraft(PlatformBrowserLoginDao dao, String title, String content) throws Exception;

    /** 由 createDraft 产物拼出"打开草稿"深链。 */
    protected abstract String deepLink(DraftCreatedResult draft);

    /** 重新打开深链并校验内容，返回第 5 个节点结果。 */
    protected abstract NodeResult verifyDeepLink(String storageState, DraftCreatedResult draft, String title, String content) throws Exception;

    @Override
    public List<NodeResult> verify(PlatformBrowserLoginEntity login, String title, String content) {
        List<NodeResult> nodes = new ArrayList<>();

        // ---- 节点1：登录态就绪 ----
        if (login == null) {
            nodes.add(NodeResult.fail("登录态就绪", "DB 无「" + platformKey() + "」登录记录，请先在浏览器登录管理登录"));
            return nodes;
        }
        if (!"LOGGED_IN".equals(login.getLoginStatus())) {
            nodes.add(NodeResult.fail("登录态就绪", "login_status=" + login.getLoginStatus() + "（非 LOGGED_IN，请重新登录）"));
            return nodes;
        }
        String account = login.getAccountName() == null ? "?" : login.getAccountName();
        nodes.add(NodeResult.pass("登录态就绪", "LOGGED_IN / 账号=" + account));
        String storageState = login.getStorageState();

        // ---- 节点2：会话可恢复 ----
        try (PlaywrightBrowserComponent.BrowserSession session = browser.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState));
             Page page = session.newPage()) {
            page.setDefaultTimeout(props.getTimeoutMillis());
            page.navigate(creatorUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForSelector(titleSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(props.getTimeoutMillis()));
            nodes.add(NodeResult.pass("会话可恢复", "storage_state 可开启会话且编辑器可达"));
        } catch (Exception e) {
            nodes.add(NodeResult.fail("会话可恢复", rootMsg(e)));
            // 会话都恢复不了，后续节点无意义
            nodes.add(NodeResult.skip("草稿创建成功", "会话恢复失败"));
            nodes.add(NodeResult.skip("平台ID已捕获", "会话恢复失败"));
            nodes.add(NodeResult.skip("深链内容可见", "会话恢复失败"));
            return nodes;
        }

        // ---- 节点3 + 节点4：草稿创建 + 平台ID ----
        PlatformBrowserLoginDao dao = PlatformLoginState.stubDao(platformKey(), login);
        DraftCreatedResult draft;
        try {
            draft = createDraft(dao, title, content);
            nodes.add(NodeResult.pass("草稿创建成功", "createDraft 无异常返回"));
        } catch (Exception e) {
            nodes.add(NodeResult.fail("草稿创建成功", rootMsg(e)));
            nodes.add(NodeResult.skip("平台ID已捕获", "草稿未创建"));
            nodes.add(NodeResult.skip("深链内容可见", "草稿未创建"));
            return nodes;
        }
        if (draft.remoteContentId() != null && !draft.remoteContentId().isBlank()) {
            nodes.add(NodeResult.pass("平台ID已捕获", "remoteContentId=" + draft.remoteContentId()));
        } else {
            // 东方财富允许退化 null，但回归里记为 FAIL 并提示去草稿箱核对
            nodes.add(NodeResult.fail("平台ID已捕获", "remoteContentId 为 null/空，请到平台草稿箱核对"));
        }
        this.lastDraftUrl = draft.draftUrl();
        this.lastRemoteContentId = draft.remoteContentId();

        // ---- 节点5：深链内容可见 ----
        try {
            nodes.add(verifyDeepLink(storageState, draft, title, content));
        } catch (Exception e) {
            nodes.add(NodeResult.fail("深链内容可见", rootMsg(e)));
        }
        return nodes;
    }

    /** 抽取异常根因链，去掉堆栈，保留可读信息（最长 240 字）。 */
    protected static String rootMsg(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c != null) {
            String m = c.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(m.length() > 240 ? m.substring(0, 240) : m);
            }
            c = c.getCause();
        }
        return sb.length() > 0 ? sb.toString() : "(无错误信息)";
    }
}
