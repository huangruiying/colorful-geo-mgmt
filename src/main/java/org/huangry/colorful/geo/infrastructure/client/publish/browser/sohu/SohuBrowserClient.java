package org.huangry.colorful.geo.infrastructure.client.publish.browser.sohu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverOutcome;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification.HumanVerificationRule;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification.HumanVerificationTakeover;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 搜狐号文章的草稿创建客户端：用数据库保存的登录态在文章编辑器建草稿，入口为 createDraft。
 * <p>在文章编辑器写入标题和正文，点“存草稿”取得平台草稿 ID，再按 ID 重新打开草稿回读确认。</p>
 * <p>新设备打开后台会被要求短信安全验证：此时请求浏览器人工接管，由用户在管理页的接管窗口完成验证，
 * 通过后把带验证结果的会话写回登录态，再继续建草稿；放弃或超时按失败处理。</p>
 * <p>不负责公开发布和登录管理；账号需已实名（未实名时“存草稿”不可用）。保存后的任何异常都按结果未确认处理，不自动重试。</p>
 */
@Slf4j
@Component
public class SohuBrowserClient {

    private static final String EDITOR_URL = "https://mp.sohu.com/mpfe/v4/contentManagement/news/addarticle?contentStatus=1";
    private static final String DRAFT_URL_PREFIX =
            "https://mp.sohu.com/mpfe/v4/contentManagement/news/addarticle?contentStatus=2&id=";
    private static final String SAVE_DRAFT_URL = "https://mp.sohu.com/mpbp/bp/news/v4/news/draft/v2";
    private static final String TITLE_SELECTOR = "input[placeholder='请输入标题（5-72字）']";
    private static final String BODY_SELECTOR = "div.ql-editor";
    /** 人工接管窗口标题中的业务名称；本客户端只做预发布建草稿。 */
    private static final String TAKEOVER_BUSINESS_NAME = "预发布";

    @Resource
    private PlatformBrowserLoginDao loginDao;
    @Resource
    private PlaywrightBrowserComponent browserComponent;
    @Resource
    private PublicationBrowserProperties properties;
    @Resource
    private HumanVerificationTakeover humanVerificationTakeover;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 使用已保存的搜狐号登录态创建文章草稿，并从编辑器回读确认。
     *
     * @param title 已审核标题
     * @param content 已审核正文
     * @return 平台草稿 ID 和编辑链接
     * @throws PublicationClientException 未登录、浏览器忙、安全验证被放弃或超时、编辑器打不开或平台明确拒绝保存
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public DraftCreated createDraft(String title, String content) {
        String storageState = requireSavedStorageState();
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("搜狐号浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只接收数据库中状态为已登录的搜狐号 Playwright 会话。
     *
     * @return 可恢复的浏览器状态
     * @throws PublicationClientException 没有已登录的会话
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("SOHU");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("搜狐号浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 在文章编辑器写入内容并存草稿，再按草稿 ID 重新打开回读。
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
            // 1. 打开文章编辑器；被跳到短信安全验证页时等用户在接管窗口完成验证
            openEditor(session, page, EDITOR_URL);
            Locator titleInput = page.locator(TITLE_SELECTOR);
            Locator editor = page.locator(BODY_SELECTOR).first();

            // 2. 写入后点“存草稿”，以保存接口返回的草稿 ID 为准；从此刻起平台可能已建稿
            draftMayExist = true;
            titleInput.fill(title);
            pasteContent(editor, content);
            Response response = page.waitForResponse(result -> result.url().startsWith(SAVE_DRAFT_URL)
                            && "POST".equals(result.request().method()),
                    () -> page.getByText("存草稿", new Page.GetByTextOptions().setExact(true)).first().click());
            String draftId = readSavedDraftId(response.text());
            String draftUrl = DRAFT_URL_PREFIX + draftId;

            // 3. 按草稿 ID 重新打开，回读标题和正文，不把编辑器本地状态当成保存结果
            verifySavedDraft(session, page, draftUrl, title, content);
            log.info("搜狐号文章草稿已创建并回读确认，remoteContentId={}", draftId);
            return new DraftCreated(draftId, draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("搜狐号草稿结果未确认，请核对草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("搜狐号文章编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 打开文章编辑器并关闭“新功能上线”提示；被要求短信安全验证时先等用户完成验证，再重新打开。
     *
     * @param session 本次浏览器会话，验证通过后从中导出新登录态
     * @param page 当前浏览器页面
     * @param url 编辑器地址（新文章或已有草稿）
     * @throws PublicationClientException 安全验证被放弃、超时，或验证后仍被要求验证
     */
    private void openEditor(PlaywrightBrowserComponent.BrowserSession session, Page page, String url) {
        navigateToEditor(page, url);

        // 识别到无法自动化处理的场景，进行人工接管
        if (HumanVerificationRule.SOHU_CLIENT_AUTH.matches(page)) {
            completeClientAuth(session, page);
            // 验证通过后平台未必回到编辑器，按原地址重新打开；仍被要求验证说明未生效，不再循环等待
            navigateToEditor(page, url);
            if (HumanVerificationRule.SOHU_CLIENT_AUTH.matches(page)) {
                throw new PublicationClientException("搜狐号完成安全验证后仍要求验证，请稍后重试");
            }
        }
        page.locator(BODY_SELECTOR).first().waitFor();

        // “表格功能上线了”等提示在编辑器加载后才出现，最多等 3 秒；不出现就直接继续
        Locator tip = page.getByText("我知道了", new Page.GetByTextOptions().setExact(true));
        try {
            tip.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3_000));
        } catch (TimeoutError exception) {
            return;
        }
        tip.first().click();
    }

    /**
     * 打开编辑器地址，等到编辑器标题出现或被跳到短信安全验证页。
     *
     * @param page 当前浏览器页面
     * @param url 编辑器地址
     */
    private void navigateToEditor(Page page, String url) {
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        Locator titleInput = page.locator(TITLE_SELECTOR);
        // 安全验证是前端加载后再跳转的，要等到两者之一出现才能判断
        page.waitForCondition(() -> HumanVerificationRule.SOHU_CLIENT_AUTH.matches(page) || titleInput.isVisible());
    }

    /**
     * 请求人工接管，等用户在接管窗口完成短信安全验证，并把带验证结果的会话写回登录态。
     *
     * @param session 本次浏览器会话
     * @param page 停在安全验证页的页面
     * @throws PublicationClientException 用户放弃或等待超时
     */
    private void completeClientAuth(PlaywrightBrowserComponent.BrowserSession session, Page page) {
        BrowserTakeoverOutcome outcome = humanVerificationTakeover.ensureHumanVerified(
                page, PublicationPlatformType.SOHU, TAKEOVER_BUSINESS_NAME);
        if (!outcome.canContinue()) {
            throw new PublicationClientException("搜狐号安全验证未完成：" + outcome.getDescription());
        }
        // 验证结果记在浏览器 Cookie 中，写回后下次预发布不再要求验证
        int updatedRows = loginDao.updateStorageState(PublicationPlatformType.SOHU.name(), session.exportStorageState());
        log.info("搜狐号安全验证已通过，登录态写回 {} 条", updatedRows);
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
     * 校验“存草稿”接口的业务结果，提取平台生成的草稿 ID。
     *
     * @param responseBody 保存接口响应，不写入日志
     * @return 正数草稿 ID
     * @throws PublicationClientException 平台明确拒绝保存（success 不为 true）
     * @throws PublicationOutcomeUnknownException 平台受理但没有返回有效草稿 ID
     */
    static String readSavedDraftId(String responseBody) {
        JSONObject response = JSON.parseObject(responseBody);
        // HTTP 成功不代表保存成功，success 为 true 才是保存成功
        if (response == null || !Boolean.TRUE.equals(response.getBoolean("success"))) {
            String reason = response == null ? null : response.getString("msg");
            throw new PublicationClientException("搜狐号草稿保存失败："
                    + (reason == null || reason.isBlank() ? "平台未确认保存成功" : reason));
        }
        String draftId = response.getString("data");
        // 保存已被受理但没有可靠 ID 时，不能报告确定失败或自动重试
        if (draftId == null || !draftId.matches("[1-9]\\d*")) {
            throw new PublicationOutcomeUnknownException("搜狐号草稿标识未确认，请核对草稿箱，勿直接重试");
        }
        return draftId;
    }

    /**
     * 按草稿 ID 重新打开编辑器，确认标题与正文已由平台保存。
     *
     * @param session 本次浏览器会话
     * @param page 当前浏览器页面
     * @param draftUrl 草稿编辑链接
     * @param title 预期标题
     * @param content 预期正文
     * @throws PublicationOutcomeUnknownException 正文与提交内容不一致
     */
    private void verifySavedDraft(PlaywrightBrowserComponent.BrowserSession session, Page page, String draftUrl,
                                  String title, String content) {
        openEditor(session, page, draftUrl);
        // 标题是远程草稿加载完成的信号，等到后再读正文
        page.waitForFunction("expected => document.querySelector(\"" + TITLE_SELECTOR + "\")?.value === expected",
                title, new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
        String savedBody = page.locator(BODY_SELECTOR).first().innerText();
        if (!normalizeParagraphs(content).equals(normalizeParagraphs(savedBody))) {
            throw new PublicationOutcomeUnknownException("搜狐号草稿正文未完整回读，请核对草稿箱，勿直接重试");
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
