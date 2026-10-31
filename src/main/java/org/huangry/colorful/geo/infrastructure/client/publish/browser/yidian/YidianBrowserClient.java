package org.huangry.colorful.geo.infrastructure.client.publish.browser.yidian;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一点号文章的草稿创建客户端：用数据库保存的登录态在文章编辑器建草稿，入口为 createDraft。
 * <p>写入标题和正文后，按平台要求选好内容声明与封面，点“存草稿”取得平台草稿 ID，再按 ID 重新打开回读确认。</p>
 * <p>平台规则：存草稿前必须选择内容声明（未选会提示“请选择内容声明”），封面选“默认”可不上传图片。
 * 内容声明固定选“内容由AI生成”：系统里的文章经大模型优化，按如实声明处理。</p>
 * <p>不负责公开发布和登录管理；保存后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class YidianBrowserClient {

    private static final String EDITOR_URL = "https://mp.yidianzixun.com/#/Writing/articleEditor";
    private static final String DRAFT_URL_PREFIX = "https://mp.yidianzixun.com/#/Writing/";
    private static final String SAVE_URL = "https://mp.yidianzixun.com/model/Article";
    private static final String TITLE_SELECTOR = "input.post-title";
    private static final String BODY_SELECTOR = "div.editor-content";
    /** 一点号文章标题长度范围；超出时平台拒绝保存或输入框截断。 */
    private static final int MIN_TITLE_LENGTH = 5;
    private static final int MAX_TITLE_LENGTH = 64;
    /** 存草稿必选的内容声明。 */
    private static final String CONTENT_STATEMENT = "内容由AI生成";

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的一点号登录态创建文章草稿，并从编辑器回读确认。
     *
     * @param title 已审核标题，5～64 字
     * @param content 已审核正文
     * @return 平台草稿 ID 和编辑链接
     * @throws PublicationClientException 标题长度不符、未登录、浏览器忙、编辑器打不开或平台明确拒绝保存
     * @throws PublicationOutcomeUnknownException 已开始保存但结果无法确认
     */
    public DraftCreated createDraft(String title, String content) {
        if (title.length() < MIN_TITLE_LENGTH || title.length() > MAX_TITLE_LENGTH) {
            throw new PublicationClientException("一点号文章标题需 5～64 字，当前 " + title.length() + " 字");
        }
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("一点资讯浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的一点资讯 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("YIDIAN");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("一点资讯浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 写入内容、选好内容声明与封面后存草稿，再按草稿 ID 重新打开回读。
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
            // 1. 打开文章编辑器，等标题和正文就绪
            page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator titleInput = page.locator(TITLE_SELECTOR);
            Locator editor = page.locator(BODY_SELECTOR).first();
            titleInput.waitFor();
            editor.waitFor();

            // 2. 写入内容并满足存草稿的前端校验：内容声明必选，封面选“默认”不需要图片
            titleInput.fill(title);
            pasteContent(editor, content);
            page.locator(".content-statement-container .item", new Page.LocatorOptions().setHasText(CONTENT_STATEMENT))
                    .first().click();
            page.locator(".cover-type .item", new Page.LocatorOptions().setHasText(Pattern.compile("^默认$")))
                    .first().click();

            // 3. 点“存草稿”，以保存接口返回的草稿 ID 为准；从此刻起平台可能已建稿
            draftMayExist = true;
            Response response = page.waitForResponse(result -> result.url().split("\\?")[0].equals(SAVE_URL)
                            && "POST".equals(result.request().method()),
                    () -> page.locator("button.mp-btn-cancel", new Page.LocatorOptions().setHasText("存草稿")).first().click());
            String draftId = readSavedDraftId(response.text());
            String draftUrl = DRAFT_URL_PREFIX + draftId + "?from=articleEditor";

            // 4. 按草稿 ID 重新打开，回读标题和正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(page, draftUrl, title, content);
            log.info("一点号文章草稿已创建并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("一点资讯草稿结果未确认，请到内容管理的草稿中核对，勿直接重试", exception);
            }
            throw new PublicationClientException("一点资讯文章编辑器无法打开或存草稿条件未满足，请检查登录态和页面", exception);
        }
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
     * 校验存草稿接口的结果，提取平台生成的草稿 ID。
     *
     * @param responseBody 保存接口响应（成功时形如 {"id":1141534006604800}），不写入日志
     * @return 正数草稿 ID
     * @throws PublicationOutcomeUnknownException 响应中没有有效草稿 ID
     */
    static String readSavedDraftId(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        String draftId = response == null ? null : response.getString("id");
        // 平台受理但没有可靠 ID 时，不能报告确定失败或自动重试
        if (draftId == null || !draftId.matches("[1-9]\\d*")) {
            throw new PublicationOutcomeUnknownException("一点资讯草稿标识未确认，请到内容管理的草稿中核对，勿直接重试");
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
            throw new PublicationOutcomeUnknownException("一点资讯草稿正文未完整回读，请到内容管理的草稿中核对，勿直接重试");
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
