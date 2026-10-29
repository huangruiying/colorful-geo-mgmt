package org.huangry.colorful.geo.infrastructure.client.publish.browser.bilibili;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 哔哩哔哩专栏的草稿创建客户端：用数据库保存的登录态在专栏编辑器建草稿，入口为 createDraft。
 * <p>在专栏编辑器写入标题和正文，点“保存为草稿”取得平台草稿 ID，再按 ID 重新打开草稿回读确认。</p>
 * <p>不负责公开发布和登录管理；保存后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class BilibiliBrowserClient {

    private static final String EDITOR_URL = "https://member.bilibili.com/york/read-editor";
    private static final String SAVE_DRAFT_URL = "https://api.bilibili.com/x/dynamic/feed/article/draft/add";
    private static final String TITLE_SELECTOR = "textarea.title-input__inner";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的哔哩哔哩登录态创建专栏草稿，并从编辑器回读确认。
     *
     * @param title 已审核标题
     * @param content 已审核正文
     * @return 平台草稿 ID 和编辑链接
     * @throws PublicationClientException 未登录、浏览器忙、编辑器打不开或平台明确拒绝保存
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public DraftCreated createDraft(String title, String content) {
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("哔哩哔哩浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的哔哩哔哩 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("BILIBILI");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("哔哩哔哩浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 在专栏编辑器写入内容并保存为草稿，再按草稿 ID 重新打开回读。
     *
     * @param storageState 数据库浏览器状态
     * @param title 草稿标题
     * @param content 草稿正文
     * @return 已确认的草稿
     */
    private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
        boolean draftMayExist = false;
        try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = session.newPage();
            page.setDefaultTimeout(properties.getTimeoutMillis());
            // 1. 直接打开专栏编辑器（创作中心页面只是把它嵌在 iframe 里），等标题和正文就绪
            page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator titleInput = page.locator(TITLE_SELECTOR);
            Locator editor = page.locator(BODY_SELECTOR).first();
            titleInput.waitFor();
            editor.waitFor();

            // 2. 写入后点“保存为草稿”，以保存接口返回的草稿 ID 为准；从此刻起平台可能已建稿
            draftMayExist = true;
            titleInput.fill(title);
            pasteContent(editor, content);
            Response response = page.waitForResponse(result -> result.url().startsWith(SAVE_DRAFT_URL)
                            && "POST".equals(result.request().method()),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("保存为草稿")).click());
            String draftId = readSavedDraftId(response.text());
            String draftUrl = EDITOR_URL + "?aid=" + draftId;

            // 3. 按草稿 ID 重新打开，回读标题和正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(page, draftUrl, title, content);
            log.info("哔哩哔哩专栏草稿已创建并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("哔哩哔哩草稿结果未确认，请核对专栏草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("哔哩哔哩专栏编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 用编辑器自身的粘贴处理写入正文，替换编辑器里的初始空段落并保留段落。
     *
     * @param editor 专栏正文编辑区
     * @param content 草稿正文
     */
    private void pasteContent(Locator editor, String content) {
        editor.click();
        editor.evaluate("""
                (editor, text) => {
                    editor.focus();
                    const range = document.createRange();
                    range.selectNodeContents(editor);
                    const selection = window.getSelection();
                    selection.removeAllRanges();
                    selection.addRange(range);
                    const clipboard = new DataTransfer();
                    clipboard.setData('text/plain', text);
                    editor.dispatchEvent(new ClipboardEvent('paste', {
                        clipboardData: clipboard, bubbles: true, cancelable: true
                    }));
                }
                """, content);
    }

    /**
     * 校验“保存为草稿”接口的业务结果，提取平台生成的草稿 ID。
     *
     * @param responseBody 保存接口响应，不写入日志
     * @return 正数草稿 ID
     * @throws PublicationClientException 平台明确拒绝保存（code 非 0）
     * @throws PublicationOutcomeUnknownException 平台受理但没有返回有效草稿 ID
     */
    static String readSavedDraftId(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        // HTTP 成功不代表保存成功，业务码 code 为 0 才是保存成功
        if (response == null || !Integer.valueOf(0).equals(response.getInteger("code"))) {
            String reason = response == null ? null : response.getString("message");
            throw new PublicationClientException("哔哩哔哩草稿保存失败："
                    + (reason == null || reason.isBlank() ? "平台未确认保存成功" : reason));
        }
        JSONObject data = response.getJSONObject("data");
        String draftId = data == null ? null : data.getString("article_id");
        // 保存已被受理但没有可靠 ID 时，不能报告确定失败或自动重试
        if (draftId == null || !draftId.matches("[1-9]\\d*")) {
            throw new PublicationOutcomeUnknownException("哔哩哔哩草稿标识未确认，请核对专栏草稿箱，勿直接重试");
        }
        return draftId;
    }

    /**
     * 按草稿 ID 重新打开编辑器，确认标题与正文已由平台保存。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 草稿编辑链接
     * @param title 预期标题
     * @param content 预期正文
     * @throws PublicationOutcomeUnknownException 正文与提交内容不一致
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String content) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // 标题是远程草稿加载完成的信号，等到后再读正文
        page.waitForFunction("expected => document.querySelector('" + TITLE_SELECTOR + "')?.value === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
        String savedBody = page.locator(BODY_SELECTOR).first().innerText();
        if (!normalizeParagraphs(content).equals(normalizeParagraphs(savedBody))) {
            throw new PublicationOutcomeUnknownException("哔哩哔哩草稿正文未完整回读，请核对专栏草稿箱，勿直接重试");
        }
    }

    /**
     * 按非空文本行比较正文；编辑器在段落之间显示的空行不算差异，文字、顺序和行内空格仍须一致。
     *
     * @param text 输入或回读正文
     * @return 只保留非空行的正文
     */
    static String normalizeParagraphs(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').strip().split("\n", -1))
                .filter(line -> !line.isBlank()).collect(Collectors.joining("\n"));
    }

    /** @param remoteContentId 平台草稿 ID @param draftUrl 草稿编辑链接 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
