package org.huangry.colorful.geo.infrastructure.client.publish.browser.smzdm;

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
 * 什么值得买原创文章的草稿创建客户端：用数据库保存的登录态在文章编辑器建草稿，入口为 createDraft。
 * <p>投稿页每次为“发布新文章”分配一个新文章编号，编辑器写入后由平台自动保存为草稿；
 * 以带正文的自动保存成功响应为准，再按编号重新打开编辑器回读确认。</p>
 * <p>不负责公开发布和登录管理；写入开始后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class SmzdmBrowserClient {

    private static final String SUBMIT_PAGE_URL = "https://post.smzdm.com/tougao/";
    private static final String EDITOR_URL_PREFIX = "https://post.smzdm.com/edit/";
    private static final String AUTO_SAVE_URL = "https://post.smzdm.com/api/editor/article/submit";
    private static final String TITLE_SELECTOR = "textarea.article-title";
    private static final String BODY_SELECTOR = "div.ProseMirror";

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的什么值得买登录态创建文章草稿，并从编辑器回读确认。
     *
     * @param title 已审核标题
     * @param content 已审核正文
     * @return 平台文章编号和编辑链接
     * @throws PublicationClientException 未登录、浏览器忙、编辑器打不开、新文章编辑器不为空或平台明确拒绝保存
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public DraftCreated createDraft(String title, String content) {
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("什么值得买浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的什么值得买 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("SMZDM");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("什么值得买浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 取新文章编号、写入内容、等待自动保存，最后重新打开回读。
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
            // 1. 投稿页的“发布新文章”链接带有本次新分配的文章编号
            page.navigate(SUBMIT_PAGE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            String articleId = readNewArticleId(page.locator("a.release-new").first().getAttribute("href"));
            String draftUrl = EDITOR_URL_PREFIX + articleId;

            // 2. 打开编辑器；只往空白的新文章写入，避免覆盖用户正在写的草稿
            openEditor(page, draftUrl);
            Locator titleInput = page.locator(TITLE_SELECTOR);
            if (!titleInput.inputValue().isBlank()) {
                throw new PublicationClientException("什么值得买新文章编辑器已有内容，请先处理草稿箱中的草稿后重试");
            }

            // 3. 写入后平台自动保存；以带上正文的自动保存成功响应为准，从此刻起平台可能已建稿
            draftMayExist = true;
            titleInput.fill(title);
            String bodyProbe = bodyProbe(content);
            Locator editor = page.locator(BODY_SELECTOR).first();
            Response saved = page.waitForResponse(response -> isAutoSaveWithBody(response.request(), bodyProbe),
                    () -> pasteContent(editor, content));
            requireAutoSaveSucceeded(saved.text());

            // 4. 重新打开同一篇文章回读标题和正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(page, draftUrl, title, content);
            log.info("什么值得买草稿已创建并回读确认，remoteContentId={}", articleId);
            return new DraftCreated(articleId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("什么值得买草稿结果未确认，请核对草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("什么值得买编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 从投稿页“发布新文章”链接中取出文章编号。
     *
     * @param href 链接地址，形如 /edit/a70p7eko
     * @return 文章编号
     * @throws PublicationClientException 投稿页没有给出新文章链接
     */
    static String readNewArticleId(String href) {
        String articleId = href == null ? "" : href.replaceFirst("^.*/edit/", "");
        if (!articleId.matches("[0-9a-z]+")) {
            throw new PublicationClientException("什么值得买投稿页没有新文章入口，请确认账号可以投稿");
        }
        return articleId;
    }

    /**
     * 打开文章编辑器，关闭首次进入时盖住编辑区的“产品全新升级”介绍弹窗。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 文章编辑链接
     */
    private void openEditor(Page page, String draftUrl) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator(TITLE_SELECTOR).waitFor();
        page.locator(BODY_SELECTOR).first().waitFor();
        // 介绍弹窗在编辑器加载后才出现，最多等 3 秒；不出现就直接继续
        Locator intro = page.getByText("立即体验", new Page.GetByTextOptions().setExact(true));
        try {
            intro.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3_000));
        } catch (TimeoutError exception) {
            return;
        }
        intro.first().click();
    }

    /**
     * 用编辑器自身的粘贴处理写入正文，保留段落。
     *
     * @param editor 正文编辑区
     * @param content 草稿正文
     */
    private void pasteContent(Locator editor, String content) {
        editor.click();
        editor.evaluate("""
                (editor, text) => {
                    editor.focus();
                    const clipboard = new DataTransfer();
                    clipboard.setData('text/plain', text);
                    editor.dispatchEvent(new ClipboardEvent('paste', {
                        clipboardData: clipboard, bubbles: true, cancelable: true
                    }));
                }
                """, content);
    }

    /**
     * 取正文第一个非空行中不会被 HTML 转义的前一段文字，用来识别包含正文的那次自动保存。
     *
     * @param content 草稿正文
     * @return 最多 20 个字符的正文片段；正文首行一开头就是需转义字符时为空
     */
    static String bodyProbe(String content) {
        String firstLine = Arrays.stream(content.split("\n")).map(String::strip)
                .filter(line -> !line.isEmpty()).findFirst().orElse("");
        // 平台把正文转成 HTML 提交，& < > 引号会被转义，片段只取它们之前的部分
        String plain = firstLine.split("[&<>\"']", 2)[0];
        return plain.length() > 20 ? plain.substring(0, 20) : plain;
    }

    /**
     * 判断请求是否是已带上正文的自动保存；标题刚写入时也会触发一次不含正文的自动保存。
     *
     * @param request 浏览器请求
     * @param bodyProbe 正文片段，可为空
     * @return 是否为包含正文的自动保存请求
     */
    static boolean isAutoSaveWithBody(Request request, String bodyProbe) {
        if (!request.url().startsWith(AUTO_SAVE_URL) || !"POST".equals(request.method()) || request.postData() == null) {
            return false;
        }
        String form = URLDecoder.decode(request.postData(), StandardCharsets.UTF_8);
        if (!form.contains("submit_type=auto_save")) return false;
        // 正文首行一开头就是会被转义的字符时片段为空，退而要求提交的正文字段不为空
        return bodyProbe.isEmpty() ? form.matches("(?s).*(^|&)content=[^&]+.*") : form.contains(bodyProbe);
    }

    /**
     * 校验自动保存接口的业务结果。
     *
     * @param responseBody 自动保存响应，不写入日志
     * @throws PublicationClientException 平台明确拒绝保存（error_code 非 0）
     */
    static void requireAutoSaveSucceeded(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        // HTTP 成功不代表保存成功，error_code 为 0 才是保存成功
        if (response == null || !Integer.valueOf(0).equals(response.getInteger("error_code"))) {
            String reason = response == null ? null : response.getString("error_msg");
            throw new PublicationClientException("什么值得买草稿保存失败："
                    + (reason == null || reason.isBlank() ? "平台未确认保存成功" : reason));
        }
    }

    /**
     * 重新打开文章编辑器，确认标题与正文已由平台保存。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 文章编辑链接
     * @param title 预期标题
     * @param content 预期正文
     * @throws PublicationOutcomeUnknownException 正文与提交内容不一致
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String content) {
        openEditor(page, draftUrl);
        // 标题是远程草稿加载完成的信号，等到后再读正文
        page.waitForFunction("expected => document.querySelector('" + TITLE_SELECTOR + "')?.value === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
        String savedBody = page.locator(BODY_SELECTOR).first().innerText();
        if (!normalizeParagraphs(content).equals(normalizeParagraphs(savedBody))) {
            throw new PublicationOutcomeUnknownException("什么值得买草稿正文未完整回读，请核对草稿箱，勿直接重试");
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

    /** @param remoteContentId 平台文章编号 @param draftUrl 文章编辑链接 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
