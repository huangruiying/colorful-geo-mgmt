package org.huangry.colorful.geo.infrastructure.client.publish.browser.imooc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 慕课网手记的草稿创建客户端：用数据库保存的登录态在 Markdown 编辑器建草稿，入口为 createDraft。
 * <p>写文章页切到 Markdown 编辑器后原样写入 Markdown 正文，平台自动保存为草稿；以带正文的自动保存成功响应取得草稿 ID，
 * 再按 ID 重新打开草稿回读确认。</p>
 * <p>不负责公开发布和登录管理；写入开始后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class ImoocBrowserClient {

    private static final String EDITOR_URL = "https://www.imooc.com/article/publish";
    private static final String DRAFT_URL_PREFIX = "https://www.imooc.com/article/draft/id/";
    private static final String SAVE_DRAFT_URL = "https://www.imooc.com/article/savedraft";
    private static final String TITLE_SELECTOR = "#article_title";
    private static final String MARKDOWN_EDITOR_SELECTOR = ".editor__inner[contenteditable=true]";

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的慕课网登录态创建手记草稿，并从编辑器回读确认。
     *
     * @param title 已审核标题
     * @param markdown 已审核 Markdown 正文
     * @return 平台草稿 ID 和编辑链接
     * @throws PublicationClientException 未登录、浏览器忙、编辑器打不开、新文章编辑器不为空或平台明确拒绝保存
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public DraftCreated createDraft(String title, String markdown) {
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("慕课网浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, markdown);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的慕课网 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("IMOOC");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("慕课网浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 切到 Markdown 编辑器写入内容，等待带正文的自动保存，再按草稿 ID 重新打开回读。
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
            // 1. 打开写文章页并关闭促销弹窗；只往空白的新文章写入，避免改动已有草稿
            page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator titleInput = page.locator(TITLE_SELECTOR);
            titleInput.waitFor();
            closePromotionPopup(page);
            if (!titleInput.inputValue().isBlank()) {
                throw new PublicationClientException("慕课网写文章页已有内容，请先处理草稿后重试");
            }

            // 2. 切到 Markdown 编辑器，正文按 Markdown 原样保存，不经富文本转换
            page.getByText("Markdown", new Page.GetByTextOptions().setExact(true)).first().click();
            Locator editor = page.locator(MARKDOWN_EDITOR_SELECTOR).first();
            editor.waitFor();

            // 3. 写入后平台自动保存；以带上正文的保存成功响应为准，从此刻起平台可能已建稿
            draftMayExist = true;
            titleInput.fill(title);
            String bodyProbe = bodyProbe(markdown);
            Response saved = page.waitForResponse(response -> isSaveWithBody(response.request(), bodyProbe),
                    () -> pasteMarkdown(editor, markdown));
            String draftId = readSavedDraftId(saved.text());
            String draftUrl = DRAFT_URL_PREFIX + draftId;

            // 4. 按草稿 ID 重新打开，回读标题和正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(page, draftUrl, title, markdown);
            log.info("慕课网手记草稿已创建并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("慕课网草稿结果未确认，请核对我的草稿，勿直接重试", exception);
            }
            throw new PublicationClientException("慕课网写文章页无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 关闭慕课网页面加载后弹出的促销广告，否则广告遮住编辑区；最多等 3 秒，不出现就继续。
     *
     * @param page 当前浏览器页面
     */
    private void closePromotionPopup(Page page) {
        Locator close = page.locator("#js-advpop-close");
        try {
            close.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3_000));
        } catch (TimeoutError exception) {
            return;
        }
        close.click();
    }

    /**
     * 通过编辑器自身的粘贴处理写入 Markdown，保留空行和列表格式。
     *
     * @param editor Markdown 编辑区
     * @param markdown 草稿正文
     */
    private void pasteMarkdown(Locator editor, String markdown) {
        editor.click();
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
     * 取正文第一个非空行的前 20 个字符，用来识别包含正文的那次自动保存（写标题时也会保存一次空正文）。
     *
     * @param markdown 草稿正文
     * @return 正文片段
     */
    static String bodyProbe(String markdown) {
        String firstLine = Arrays.stream(markdown.split("\n")).map(String::strip)
                .filter(line -> !line.isEmpty()).findFirst().orElse("");
        return firstLine.length() > 20 ? firstLine.substring(0, 20) : firstLine;
    }

    /**
     * 判断请求是否是已带上正文的 Markdown 自动保存。
     *
     * @param request 浏览器请求
     * @param bodyProbe 正文片段
     * @return 是否为包含正文的保存请求
     */
    static boolean isSaveWithBody(Request request, String bodyProbe) {
        if (!request.url().startsWith(SAVE_DRAFT_URL) || !"POST".equals(request.method()) || request.postData() == null) {
            return false;
        }
        String form = URLDecoder.decode(request.postData(), StandardCharsets.UTF_8);
        // editor=0 是 Markdown 编辑器；提交的正文是 Markdown 原文，片段不会被转义
        return form.contains("editor=0") && form.contains(bodyProbe);
    }

    /**
     * 校验保存接口的业务结果，提取平台生成的草稿 ID。
     *
     * @param responseBody 保存接口响应，不写入日志
     * @return 正数草稿 ID
     * @throws PublicationClientException 平台明确拒绝保存（result 非 0）
     * @throws PublicationOutcomeUnknownException 平台受理但没有返回有效草稿 ID
     */
    static String readSavedDraftId(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        // HTTP 成功不代表保存成功，result 为 0 才是保存成功
        if (response == null || !Integer.valueOf(0).equals(response.getInteger("result"))) {
            String reason = response == null ? null : response.getString("msg");
            throw new PublicationClientException("慕课网草稿保存失败："
                    + (reason == null || reason.isBlank() ? "平台未确认保存成功" : reason));
        }
        String draftId = response.getString("data");
        // 保存已被受理但没有可靠 ID 时，不能报告确定失败或自动重试
        if (draftId == null || !draftId.matches("[1-9]\\d*")) {
            throw new PublicationOutcomeUnknownException("慕课网草稿标识未确认，请核对我的草稿，勿直接重试");
        }
        return draftId;
    }

    /**
     * 按草稿 ID 重新打开编辑页，确认标题与 Markdown 正文已由平台保存。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 草稿编辑链接
     * @param title 预期标题
     * @param markdown 预期正文
     * @throws PublicationOutcomeUnknownException 正文与提交内容不一致
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String markdown) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // 标题是远程草稿加载完成的信号，等到后再读正文
        page.waitForFunction("expected => document.querySelector('" + TITLE_SELECTOR + "')?.value === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
        // textContent 保留 Markdown 原始换行，innerText 会在段落间多出空行
        String savedBody = page.locator(MARKDOWN_EDITOR_SELECTOR).first().textContent();
        if (!normalizeParagraphs(markdown).equals(normalizeParagraphs(savedBody))) {
            throw new PublicationOutcomeUnknownException("慕课网草稿正文未完整回读，请核对我的草稿，勿直接重试");
        }
    }

    /**
     * 按非空文本行比较正文；编辑器显示的空行不算差异，文字、顺序和行内空格仍须一致。
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
