package org.huangry.colorful.geo.infrastructure.client.publish.browser.csdn;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
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

import java.util.List;
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
    private static final String SAVE_ARTICLE_URL = "https://bizapi.csdn.net/blog-console-api/v3/mdeditor/saveArticle";

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
            writeDraftContent(page, title, markdown);
            // 3. 保存响应中的业务结果和 ID 优先于页面跳转；平台拒绝时直接说明原因。
            Response response = page.waitForResponse(result -> result.url().equals(SAVE_ARTICLE_URL)
                            && "POST".equals(result.request().method()),
                    () -> page.locator("button.btn-save").click());
            String draftId = readSavedDraftId(response.text());
            String draftUrl = "https://editor.csdn.net/md?articleId=" + draftId;

            // 4. 重新打开草稿并等待远程内容加载完成，不能把初始欢迎页当成保存结果。
            verifySavedDraft(page, draftUrl, title, markdown);
            log.info("CSDN 草稿已创建并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("CSDN 草稿结果未确认，请核对草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("CSDN 编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 确认标题编辑并通过编辑器的粘贴事件写入 Markdown，保留空行和列表格式。
     *
     * @param page 当前编辑页
     * @param title 草稿标题
     * @param markdown 完整 Markdown 正文
     */
    private void writeDraftContent(Page page, String title, String markdown) {
        // 按 Enter 确认标题，并等展示值更新；只触发失焦仍可能早于内部保存模型更新。
        var titleInput = page.locator("input[placeholder*='请输入文章标题']");
        titleInput.fill(title);
        titleInput.press("Enter");
        page.waitForFunction("expected => document.querySelector('.article-bar__title-display')?.innerText === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));

        // 清空欢迎正文后调用编辑器自身的纯文本粘贴处理，避免 fill 丢行、Enter 自动续写列表。
        var editor = page.locator("pre.editor__inner[contenteditable=true]");
        editor.click();
        page.keyboard().press("ControlOrMeta+A");
        page.keyboard().press("Backspace");
        editor.evaluate("""
                (editor, markdown) => {
                    const clipboard = new DataTransfer();
                    clipboard.setData('text/plain', markdown);
                    editor.dispatchEvent(new ClipboardEvent('paste', {
                        clipboardData: clipboard, bubbles: true, cancelable: true
                    }));
                }
                """, markdown);
    }

    /**
     * 校验保存接口的业务结果，提取平台生成的草稿 ID。
     *
     * @param responseBody 保存接口响应，不写入日志
     * @return 数字草稿 ID；明确拒绝与无法确认分别抛出对应异常
     */
    static String readSavedDraftId(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        // HTTP 成功不代表保存成功，例如频率限制通过 code=400 返回。
        if (response == null || !Integer.valueOf(200).equals(response.getInteger("code"))) {
            String reason = response == null ? null : response.getString("msg");
            throw new PublicationClientException("CSDN 草稿保存失败："
                    + (reason == null || reason.isBlank() ? "平台未确认保存成功" : reason));
        }
        JSONObject data = response.getJSONObject("data");
        String draftId = data == null ? null : data.getString("id");
        // 保存已被受理但没有可靠 ID 时，不能报告确定失败或自动重试。
        if (draftId == null || !draftId.matches("[1-9]\\d*")) {
            throw new PublicationOutcomeUnknownException("CSDN 草稿标识未确认，请核对草稿箱，勿直接重试");
        }
        return draftId;
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
        // 先等远程草稿全文加载；textContent 保留高亮 DOM 中的原始换行，innerText 会增加段落空行。
        page.waitForFunction("""
                expected => {
                    const title = document.querySelector('.article-bar__title-display')?.innerText;
                    const body = document.querySelector('pre.editor__inner')?.textContent;
                    const normalize = value => (value || '').replace(/\\r\\n?/g, '\\n').trimEnd();
                    return title === expected[0] && normalize(body) === normalize(expected[1]);
                }
                """, List.of(title, markdown),
                new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
    }

    /** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
