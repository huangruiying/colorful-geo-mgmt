package org.huangry.colorful.geo.infrastructure.client.publish.browser.csdn;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用数据库保存的 CSDN 登录态创建 Markdown 草稿；入口为 createDraft。
 * 只在重新打开编辑页并回读标题、正文后确认成功，不执行公开发布或管理登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsdnBrowserClient {

    private static final String EDITOR_URL = "https://editor.csdn.net/md/";
    private static final Pattern DRAFT_URL = Pattern.compile(
            "^https://editor\\.csdn\\.net/md/?\\?articleId=(\\d+)(?:&.*)?$");

    private final PlatformBrowserLoginDao loginDao;
    private final PlaywrightBrowserComponent browserComponent;
    private final PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的 CSDN 登录态创建草稿，并从平台编辑页回读确认。
     *
     * @param title 已审核标题
     * @param markdown 已审核 Markdown 正文
     * @return 平台草稿 ID 和编辑链接
     */
    public DraftCreated createDraft(String title, String markdown) {
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("CSDN 浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, markdown);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的 CSDN Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("CSDN");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("CSDN 浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 将内容写入 CSDN 编辑器，点击保存后重新打开相同草稿核对内容。
     *
     * @param storageState 数据库浏览器状态
     * @param title 草稿标题
     * @param markdown 草稿正文
     * @return 已确认的草稿
     */
    private DraftCreated createDraftInBrowser(String storageState, String title, String markdown) {
        boolean draftMayExist = false;
        try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = session.newPage();
            page.setDefaultTimeout(properties.getTimeoutMillis());
            // 1. 打开已登录的 Markdown 编辑器，定位真实可编辑的标题与正文。
            page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator(".article-bar__title-display").click();
            page.locator("input[placeholder*='请输入文章标题']").waitFor();
            page.locator("pre.editor__inner[contenteditable=true]").waitFor();
            // 2. 标题输入后平台可能自动建稿；后续异常均不得自动重试。
            draftMayExist = true;
            page.locator("input[placeholder*='请输入文章标题']").fill(title);
            page.locator("pre.editor__inner[contenteditable=true]").fill(markdown);
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("保存草稿")).click();
            // 3. 只接受平台生成的数字草稿 ID，重新打开并回读内容。
            page.waitForURL(url -> DRAFT_URL.matcher(url).matches());
            String draftUrl = page.url();
            Matcher draftId = DRAFT_URL.matcher(draftUrl);
            if (!draftId.matches()) {
                throw new PublicationOutcomeUnknownException("CSDN 草稿标识未确认，请核对草稿箱，勿直接重试");
            }
            verifySavedDraft(page, draftUrl, title, markdown);
            log.info("CSDN 草稿已创建并回读确认，remoteContentId={}", draftId.group(1));
            return new DraftCreated(draftId.group(1), draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("CSDN 草稿结果未确认，请核对草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("CSDN 编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 回读同一篇草稿，防止把编辑器的本地乐观状态误报为保存成功。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 平台返回的草稿地址
     * @param title 预期标题
     * @param markdown 预期正文
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String markdown) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator(".article-bar__title-display").waitFor();
        page.waitForFunction("() => document.querySelector('pre.editor__inner')?.innerText?.trim().length > 0");
        String savedTitle = page.locator(".article-bar__title-display").innerText();
        String savedMarkdown = page.locator("pre.editor__inner").innerText().stripTrailing();
        // 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
        if (!title.equals(savedTitle) || !markdown.stripTrailing().equals(savedMarkdown)) {
            throw new PublicationOutcomeUnknownException("CSDN 草稿内容未完整回读，请核对草稿箱，勿直接重试");
        }
    }

    /** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
