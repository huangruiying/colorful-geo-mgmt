package org.huangry.colorful.geo.infrastructure.client.publish.browser.douyin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONPath;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
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

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 抖音文章的草稿创建客户端：用数据库保存的登录态在抖音文章编辑器暂存草稿，入口为 createDraft。
 * <p>抖音文章只有一个草稿位：已有未发布的文章时直接拒绝，不覆盖用户的草稿。写入后点“暂存离开”，
 * 再从“继续编辑”重新打开草稿，以地址中的 creation_id 作为草稿 ID 并回读标题、正文确认。</p>
 * <p>不负责公开发布和登录管理；写入开始后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class DouyinBrowserClient {

    /** 作品发布页的“发布文章”标签：加载时查询草稿，有未发布文章时显示“继续编辑”。 */
    private static final String ARTICLE_TAB_URL = "https://creator.douyin.com/creator-micro/content/upload?default-tab=5";
    /** 发布页加载时查询未发布文章、编辑器自动保存共用的草稿接口路径。 */
    private static final String DRAFT_API_PATH = "/web/api/media/aweme/draft";
    private static final String TITLE_SELECTOR = "input[placeholder='请输入文章标题，最多不超过30个字']";
    private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";
    /** 抖音文章标题上限；超出时输入框会截断，导致保存的标题与确认稿不一致。 */
    private static final int MAX_TITLE_LENGTH = 30;

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的抖音登录态暂存文章草稿，并从“继续编辑”回读确认。
     *
     * @param title 已审核标题，不超过 30 字
     * @param content 已审核正文
     * @return 平台草稿 ID（creation_id）和编辑链接
     * @throws PublicationClientException 标题超长、未登录、浏览器忙、已有未发布的文章或编辑器打不开
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public DraftCreated createDraft(String title, String content) {
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new PublicationClientException("抖音文章标题最多 30 字，当前 " + title.length() + " 字，请缩短后重试");
        }
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("抖音浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的抖音 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("DOUYIN");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("抖音浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 确认草稿位空闲后写入文章并暂存，再从“继续编辑”打开回读。
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
            // 1. 打开“发布文章”标签，以页面加载时的草稿查询结果判断草稿位；
            //    抖音只有一个草稿位，已有未发布的文章时拒绝，写入会覆盖用户的草稿
            Response draftQuery = page.waitForResponse(DouyinBrowserClient::isDraftQuery, () -> page.navigate(
                    ARTICLE_TAB_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)));
            if (hasUnpublishedArticle(draftQuery.text())) {
                throw new PublicationClientException("抖音只能保存一篇文章草稿，当前已有未发布的文章，请先发布或删除后重试");
            }
            Locator newArticle = page.getByText("我要发文", new Page.GetByTextOptions().setExact(true)).first();
            newArticle.waitFor();

            // 2. 进入新文章编辑器，写入标题与正文；从此刻起平台可能已自动保存草稿
            newArticle.click();
            Locator titleInput = page.locator(TITLE_SELECTOR);
            Locator editor = page.locator(BODY_SELECTOR).first();
            titleInput.waitFor();
            editor.waitFor();
            draftMayExist = true;
            titleInput.fill(title);
            pasteContent(editor, content);

            // 3. 点“暂存离开”保存并回到发布页，再从“继续编辑”打开同一篇草稿
            page.getByText("暂存离开", new Page.GetByTextOptions().setExact(true)).first().click();
            page.waitForURL(url -> url.contains("/content/upload"));
            page.getByText("继续编辑", new Page.GetByTextOptions().setExact(true)).first().click();
            page.waitForURL(url -> url.contains("creation_id="));
            String draftId = readCreationId(page.url());
            String draftUrl = page.url();

            // 4. 回读平台保存的标题与正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(page, title, content);
            log.info("抖音文章草稿已暂存并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("抖音草稿结果未确认，请到发布文章页查看未发布的文章，勿直接重试", exception);
            }
            throw new PublicationClientException("抖音文章编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 判断响应是否为发布页加载时的草稿查询（先有一次 307 跳转，以 200 的那次为准）。
     *
     * @param response 浏览器响应
     * @return 是否为草稿查询的最终响应
     */
    static boolean isDraftQuery(Response response) {
        return response.url().contains(DRAFT_API_PATH) && "POST".equals(response.request().method())
                && response.status() == 200;
    }

    /**
     * 按草稿查询结果判断是否已有未发布的文章；“继续编辑”提示要等这次查询返回才出现，不能以提示为准。
     *
     * @param responseBody 草稿查询响应，不写入日志
     * @return 响应中有带 creation_id 的草稿时为 true
     */
    static boolean hasUnpublishedArticle(String responseBody) {
        Object creationId = JSONPath.eval(JSON.parseObject(responseBody), "$.draft.creation_id");
        return creationId != null && !creationId.toString().isBlank();
    }

    /**
     * 用编辑器自身的粘贴处理写入正文，保留段落。
     *
     * @param editor 文章正文编辑区
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
     * 从“继续编辑”打开的草稿地址中取出 creation_id。
     *
     * @param draftUrl 草稿编辑地址
     * @return 草稿 ID
     * @throws PublicationOutcomeUnknownException 地址中没有 creation_id
     */
    static String readCreationId(String draftUrl) {
        String query = URI.create(draftUrl).getRawQuery();
        String creationId = query == null ? "" : Arrays.stream(query.split("&"))
                .filter(pair -> pair.startsWith("creation_id=")).map(pair -> pair.substring("creation_id=".length()))
                .findFirst().orElse("");
        if (!creationId.matches("[0-9A-Za-z_-]+")) {
            throw new PublicationOutcomeUnknownException("抖音草稿标识未确认，请到发布文章页查看未发布的文章，勿直接重试");
        }
        return creationId;
    }

    /**
     * 在“继续编辑”打开的草稿中回读标题与正文。
     *
     * @param page 当前草稿编辑页
     * @param title 预期标题
     * @param content 预期正文
     * @throws PublicationOutcomeUnknownException 正文与提交内容不一致
     */
    private void verifySavedDraft(Page page, String title, String content) {
        // 标题是远程草稿加载完成的信号，等到后再读正文
        page.waitForFunction("expected => document.querySelector(\"" + TITLE_SELECTOR + "\")?.value === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
        String savedBody = page.locator(BODY_SELECTOR).first().innerText();
        if (!normalizeParagraphs(content).equals(normalizeParagraphs(savedBody))) {
            throw new PublicationOutcomeUnknownException("抖音草稿正文未完整回读，请到发布文章页核对，勿直接重试");
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

    /** @param remoteContentId 平台草稿 ID（creation_id） @param draftUrl 草稿编辑链接 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
