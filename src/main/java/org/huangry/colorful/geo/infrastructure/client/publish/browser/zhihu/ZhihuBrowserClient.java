package org.huangry.colorful.geo.infrastructure.client.publish.browser.zhihu;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 知乎浏览器操作客户端：使用数据库保存的知乎登录态，创建文章草稿并公开发布已有草稿。
 *
 * <p>prePublish 导入 Markdown 并回读核验草稿；publish 仅对已有草稿执行公开发布。
 * 两者共用同一把浏览器锁，保证同一服务实例内知乎浏览器动作全局串行；
 * 不扫码登录、不自动重试结果不确定的请求，失败只提示人工核对。</p>
 *
 * @author huangry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZhihuBrowserClient {

    private static final String WRITE_URL = "https://zhuanlan.zhihu.com/write";
    private static final String ARTICLE_URL = "https://zhuanlan.zhihu.com/p/";
    private static final Pattern DRAFT_URL = Pattern.compile(
            "^https://zhuanlan\\.zhihu\\.com/p/(\\d{1,30})/edit(?:\\?.*)?$");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^]]*]\\(([^\\s)]+)[^)]*\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile(
            "(?i)<img\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");

    private final PublicationBrowserProperties properties;
    private final PlaywrightBrowserComponent browserComponent;
    private final PlatformBrowserLoginDao loginDao;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 导入人工确认的 Markdown，并在重新打开后确认草稿内容已保存。
     *
     * @param title 草稿标题
     * @param markdown 草稿正文
     * @return 知乎草稿标识及编辑链接
     */
    public DraftCreated createDraft(String title, String markdown) {
        int expectedImages = countSupportedImages(title, markdown);
        PlatformBrowserLoginEntity saved = requireSavedLogin();
        // 同一服务实例只允许一个知乎浏览器动作（建草稿或发布）串行执行。
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("知乎浏览器正在处理其他任务，请稍后处理");
        }
        Path article = null;
        try {
            // 1. 编辑器只接受文件导入 Markdown；临时文件仅用于本次浏览器任务。
            article = Files.createTempFile("geo-zhihu-draft-", ".md");
            Files.writeString(article, markdown, StandardCharsets.UTF_8);
            // 2. 恢复数据库登录态，在隔离上下文中创建并回读草稿。
            return createInBrowser(saved.getStorageState(), title, markdown, article, expectedImages);
        } catch (IOException exception) {
            throw new PublicationClientException("知乎草稿临时文件读写失败", exception);
        } finally {
            deleteArticle(article);
            browserLock.unlock();
        }
    }

    /**
     * 打开已有草稿并执行公开发布，只有跳转到对应文章页才返回成功。
     *
     * @param remoteContentId prePublish 返回的知乎文章草稿标识
     * @return 经页面跳转核验的公开文章链接
     */
    public String publishDraft(String remoteContentId) {
        // 1. 先校验目标与数据库登录态，不合法输入不触达浏览器。
        validateDraftId(remoteContentId);
        String storageState = requireSavedLogin().getStorageState();
        // 2. 同一服务实例只允许一个知乎浏览器动作串行执行。
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("知乎浏览器正在处理其他任务，请稍后处理");
        }
        try {
            // 3. 执行一次发布；不确定结果只提示核对，不在此处重试。
            return publishInBrowser(remoteContentId, storageState);
        } finally {
            browserLock.unlock();
        }
    }

    /**
     * 只使用已保存为登录成功的 Playwright 会话，避免误用旧版密文或其他平台账号。
     *
     * @return 可恢复的知乎登录记录
     */
    private PlatformBrowserLoginEntity requireSavedLogin() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("ZHIHU");
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("知乎浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved;
    }

    /**
     * 创建草稿并重新打开核验；填入标题后平台可能已经生成草稿，异常不得自动重试。
     *
     * @param storageState 数据库浏览器会话
     * @param title 草稿标题
     * @param markdown 草稿正文
     * @param article 临时 Markdown 文件
     * @param expectedImages 正文预期图片数量
     * @return 平台明确保存的草稿
     */
    private DraftCreated createInBrowser(String storageState, String title, String markdown,
                                         Path article, int expectedImages) {
        boolean draftMayExist = false;
        try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = session.newPage();
            page.setDefaultTimeout(properties.getTimeoutMillis());
            page.navigate(WRITE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            // 1. 先确认进入知乎写文章页，再写标题，避免登录失效时误报成功。
            page.locator("textarea[placeholder*='请输入标题']").waitFor();
            page.locator("[contenteditable=true][role=textbox]").waitFor();
            draftMayExist = true;
            page.locator("textarea[placeholder*='请输入标题']").fill(title);
            // 2. 通过知乎原生 MD 导入保留标题、列表和远程图片的结构。
            page.locator("button[aria-label='导入']").click();
            page.locator("button[aria-label='导入文档']").click();
            page.locator("input[type=file][accept*='.markdown']").setInputFiles(article);
            page.waitForURL(url -> DRAFT_URL.matcher(url).matches());
            waitForImportedImages(page, expectedImages);
            // 3. 重新打开平台草稿，只有标题、正文和图片都回读成功才认定预发布成功。
            page.waitForTimeout(5000);
            String draftUrl = page.url();
            Matcher draftId = DRAFT_URL.matcher(draftUrl);
            if (!draftId.matches()) {
                throw new PublicationOutcomeUnknownException("知乎草稿链接未确认，请先核对知乎草稿箱，勿直接重试");
            }
            log.info("知乎草稿等待回读，remoteContentId={}，expectedImages={}",
                    draftId.group(1), expectedImages);
            verifySavedDraft(page, draftUrl, title, markdown, expectedImages);
            log.info("知乎草稿已创建并回读确认，remoteContentId={}", draftId.group(1));
            return new DraftCreated(draftId.group(1), draftUrl);
        } catch (PlaywrightException exception) {
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("知乎草稿结果未确认，请先核对知乎草稿箱，勿直接重试", exception);
            }
            throw new PublicationClientException("知乎编辑器无法打开，请检查浏览器登录态和网络", exception);
        }
    }

    /**
     * 在数据库会话恢复的隔离浏览器中执行一次发布；点击后异常保留结果不确定语义。
     * @param remoteContentId 已有草稿标识
     * @param storageState 数据库保存的知乎浏览器会话
     * @return 平台公开文章链接
     */
    private String publishInBrowser(String remoteContentId, String storageState) {
        String publishedUrl = ARTICLE_URL + remoteContentId;
        boolean submissionStarted = false;
        try (PlaywrightBrowserComponent.BrowserSession session =
                     browserComponent.openBrowser(new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = session.newPage();
            page.setDefaultTimeout(properties.getTimeoutMillis());
            // 1. 只打开指定草稿，失效登录态或错误页面不会进入发布操作。
            Locator publishButton = openDraft(page, publishedUrl);
            // 2. 真实点击前标记结果可能不确定；调用方不得自动重试。
            submissionStarted = true;
            log.info("开始公开发布知乎草稿，remoteContentId={}", remoteContentId);
            publishButton.click();
            // 3. 未发布草稿的公开 URL 会跳回首页；重新打开文章页确认不是一次临时跳转。
            verifyPublicUrl(page, publishedUrl);
            log.info("知乎草稿公开发布已确认，remoteContentId={}", remoteContentId);
            return publishedUrl;
        } catch (PlaywrightException exception) {
            // 点击可能已被平台受理，超时后不能把不确定结果当作确定失败。
            if (submissionStarted) {
                throw new PublicationOutcomeUnknownException(
                        "知乎发布操作结果未确认，请核对该草稿，勿直接重试", exception);
            }
            throw new PublicationClientException(
                    "知乎草稿页无法打开，请检查数据库保存的登录态和运行环境", exception);
        }
    }

    /**
     * 打开指定草稿并定位唯一可用的发布按钮，不在此方法中触发写操作。
     * @param page 本次浏览器页面
     * @param publishedUrl 对应文章的公开地址
     * @return 已就绪的发布按钮
     */
    private Locator openDraft(Page page, String publishedUrl) {
        page.navigate(publishedUrl + "/edit");
        Locator button = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("发布").setExact(true));
        button.waitFor();
        if (!page.url().startsWith(publishedUrl + "/edit") || !button.isEnabled()) {
            throw new PublicationClientException("知乎草稿页未就绪，请检查草稿和浏览器登录态");
        }
        return button;
    }

    /**
     * 核验点击后的跳转并重新访问文章页，避免把草稿预览误记为公开发布。
     * @param page 执行发布动作的页面
     * @param publishedUrl 期望的公开文章地址
     */
    private void verifyPublicUrl(Page page, String publishedUrl) {
        page.waitForURL(url -> url.equals(publishedUrl) || url.startsWith(publishedUrl + "?"),
                new Page.WaitForURLOptions().setTimeout(properties.getTimeoutMillis()));
        page.navigate(publishedUrl);
        if (!page.url().equals(publishedUrl) && !page.url().startsWith(publishedUrl + "?")) {
            throw new PublicationOutcomeUnknownException("知乎公开文章链接尚不可访问，请核对平台审核状态，勿直接重试");
        }
    }

    /**
     * 等待导入后的图片节点出现；无图片正文不额外等待。
     *
     * @param page 当前编辑页
     * @param expectedImages 预期图片数量
     */
    private void waitForImportedImages(Page page, int expectedImages) {
        if (expectedImages > 0) {
            page.waitForFunction("() => document.querySelectorAll('[contenteditable=true] img').length >= "
                    + expectedImages);
        }
    }

    /**
     * 回读同一草稿，防止只凭浏览器当前的乐观 UI 状态返回成功。
     *
     * @param page 当前编辑页
     * @param draftUrl 知乎草稿编辑链接
     * @param title 预期标题
     * @param markdown 预期 Markdown
     * @param expectedImages 预期图片数量
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String markdown,
                                  int expectedImages) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // 编辑器先渲染空输入框，再异步回填草稿；等待内容加载后才判断保存结果。
        page.waitForFunction("() => Boolean(document.querySelector('textarea[placeholder*=\"请输入标题\"]')?.value)");
        String expectedText = firstVisibleText(markdown);
        if (!expectedText.isEmpty()) {
            page.waitForFunction("() => Boolean(document.querySelector('[contenteditable=true][role=textbox]')?.innerText.trim())");
        }
        waitForImportedImages(page, expectedImages);
        String savedTitle = page.locator("textarea[placeholder*='请输入标题']").inputValue();
        String savedText = page.locator("[contenteditable=true][role=textbox]").innerText();
        int savedImages = page.locator("[contenteditable=true][role=textbox] img").count();
        // 图片、标题或正文有一项未写回，就保留待核对状态，不把不完整草稿标成成功。
        boolean urlMatches = DRAFT_URL.matcher(page.url()).matches();
        boolean titleMatches = title.equals(savedTitle);
        boolean textMatches = expectedText.isEmpty() || savedText.contains(expectedText);
        if (!urlMatches || !titleMatches || savedImages < expectedImages || !textMatches
                || (savedText.isBlank() && savedImages == 0)) {
            log.warn("知乎草稿回读不完整，urlMatches={}，titleMatches={}，savedImages={}，expectedImages={}，textMatches={}，bodyEmpty={}",
                    urlMatches, titleMatches, savedImages, expectedImages, textMatches, savedText.isBlank());
            throw new PublicationOutcomeUnknownException("知乎草稿内容未完整回读，请核对草稿箱，勿直接重试");
        }
    }

    /**
     * 从 Markdown 提取一段可见正文，用于回读时识别导入失败而非逐字比较富文本。
     *
     * @param markdown 原始 Markdown
     * @return 首段可见文本片段；纯图片正文返回空串
     */
    private String firstVisibleText(String markdown) {
        return markdown.lines().map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("![") && !line.startsWith("<img")
                        && !line.startsWith("```"))
                .map(line -> line.replaceFirst("^#{1,6}\\s*", "")
                        .replaceFirst("^([-*+]|[0-9]+\\.)\\s*", "")
                        .replaceAll("<[^>]+>", "").replaceAll("[*_`>#]", "").trim())
                .filter(line -> !line.isBlank())
                .map(line -> line.substring(0, Math.min(line.length(), 20)))
                .findFirst().orElse("");
    }

    /**
     * 统计知乎 MD 导入可保真的远程图片；本地或内嵌图片提前拒绝，避免静默丢图。
     *
     * @param title 待保存标题
     * @param markdown 待导入正文
     * @return 预期图片数量
     */
    int countSupportedImages(String title, String markdown) {
        if (title == null || title.isBlank() || title.length() > 100
                || markdown == null || markdown.isBlank()) {
            throw new PublicationClientException("知乎草稿标题须在 1～100 字内，正文不能为空");
        }
        int count = countRemoteImages(MARKDOWN_IMAGE.matcher(markdown));
        count += countRemoteImages(HTML_IMAGE.matcher(markdown));
        if (count != countOccurrences(markdown, "![")
                + countOccurrences(markdown.toLowerCase(Locale.ROOT), "<img")) {
            throw new PublicationClientException("知乎草稿图片语法无法识别，请使用完整的远程图片链接");
        }
        return count;
    }

    /**
     * 只允许已验证可由知乎导入的 HTTP 图片链接。
     *
     * @param matcher Markdown 或 HTML 图片来源
     * @return 图片数量
     */
    private int countRemoteImages(Matcher matcher) {
        int count = 0;
        while (matcher.find()) {
            String source = matcher.group(1);
            if (!source.startsWith("https://") && !source.startsWith("http://")) {
                throw new PublicationClientException("知乎草稿暂不支持本地或内嵌图片，请先换成远程图片链接");
            }
            count++;
        }
        return count;
    }

    /**
     * 统计原文图片标记，确保未被识别的图片也不会被静默忽略。
     *
     * @param text 原始正文
     * @param marker 图片标记
     * @return 出现次数
     */
    private int countOccurrences(String text, String marker) {
        int count = 0;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) {
            count++;
        }
        return count;
    }

    /**
     * 只允许知乎数字草稿标识，防止外部输入改变导航目标。
     * @param remoteContentId 已有草稿标识
     */
    private void validateDraftId(String remoteContentId) {
        if (remoteContentId == null || !remoteContentId.matches("[0-9]{1,30}")) {
            throw new PublicationClientException("知乎草稿标识必须为数字");
        }
    }

    /**
     * 删除本次导入用的临时正文文件；清理失败不改变平台草稿结果。
     *
     * @param article 临时文件路径，可能尚未创建
     */
    private void deleteArticle(Path article) {
        if (article == null) {
            return;
        }
        try {
            Files.deleteIfExists(article);
        } catch (IOException exception) {
            log.warn("知乎草稿临时文件清理失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /** 已核验保存的知乎草稿标识和编辑链接。 */
    public record DraftCreated(String remoteContentId, String draftUrl) { }
}
