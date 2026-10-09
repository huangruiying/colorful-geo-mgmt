package org.huangry.colorful.geo.infrastructure.client.publish.browser.jianshu;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
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
 * <pre>
 * 使用数据库保存的简书登录态创建文章草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复简书 Playwright 会话
 * - 在简书写作页新建文章、写入标题与正文、等待自动保存生成 note 标识
 * - 重新打开同一篇草稿回读标题与正文，确认平台已落库
 *
 * 核心入口：
 * - createDraft(String title, String content)
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：简书写作页为前端单页应用（hash 路由），会自动打开最近一篇笔记；
 * 编辑器无 contenteditable、无“请输入标题”placeholder，标题为非“文集名”的文本 input，
 * 正文为 Markdown 源文本域 textarea#arthur-editor。以上选择器已于 2026-10-08
 * 在已登录会话中实测通过（新建→写入→回读→删除清理）。失败一律按不确定结果处理，
 * 绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JianshuBrowserClient {

	private static final String WRITER_URL = "https://www.jianshu.com/writer";
	private static final Pattern NOTE_ID = Pattern.compile("notes/(\\d+)");

	// 2026-10-08 已登录会话实测：简书编辑器无 contenteditable、无“请输入标题”placeholder。
	// 标题是左侧面板中除“文集名”外的唯一文本 input（无 name、无 placeholder，默认值为当天日期）；
	// 正文是 Markdown 源文本域 textarea#arthur-editor；点“新建文章”后 URL 立即切换为 notes/{id}。
	private static final String TITLE_SELECTOR = "input:not([placeholder=\"请输入文集名...\"])";
	private static final String BODY_SELECTOR = "textarea#arthur-editor";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的简书登录态创建草稿，并从平台写作页回读确认。
	 *
	 * @param title   已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿 ID 和写作页链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("简书浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的简书 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("JIANSHU");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("简书浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在简书写作页新建文章、写入内容并等待自动保存生成 note 标识，最后回读核验。
	 *
	 * @param storageState 数据库浏览器状态
	 * @param title       草稿标题
	 * @param content     草稿正文
	 * @return 已确认的草稿
	 */
	private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
		boolean draftMayExist = false;
		try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
				new Browser.NewContextOptions().setStorageState(storageState))) {
			Page page = session.newPage();
			page.setDefaultTimeout(properties.getTimeoutMillis());
			// 1. 打开已登录的简书写作页；SPA 会自动打开最近一篇笔记（URL 已含 notes/{id}），
			//    先记录旧标识，避免把旧笔记误认成新建草稿。
			page.navigate(WRITER_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			Matcher existing = NOTE_ID.matcher(page.url());
			String existingNoteId = existing.find() ? existing.group(1) : null;
			// 2. 点击“新建文章”（精确匹配，避免误点“在下方新建文章”）；点击后服务端已创建空笔记。
			page.getByText("新建文章", new Page.GetByTextOptions().setExact(true)).first().click();
			draftMayExist = true;
			// 3. 等待 URL 切换为与旧标识不同的新建笔记 id。
			page.waitForURL(url -> {
				Matcher match = NOTE_ID.matcher(url);
				return match.find() && !match.group(1).equals(existingNoteId);
			});
			// 4. 写入标题与正文；简书自动保存（页首显示“已保存”）。
			Locator titleInput = page.locator(TITLE_SELECTOR).first();
			Locator bodyInput = page.locator(BODY_SELECTOR).first();
			titleInput.waitFor();
			bodyInput.waitFor();
			titleInput.fill(title);
			bodyInput.fill(content);
			String draftUrl = page.url();
			String noteId = readNoteId(draftUrl);
			verifySavedDraft(page, draftUrl, title, content);
			log.info("简书草稿已创建并回读确认，remoteContentId={}", noteId);
			return new DraftCreated(noteId, draftUrl);
		} catch (PlaywrightException exception) {
			// 写入开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("简书草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("简书编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 从简书写作页链接提取数字 note 标识；无法确认时不伪造草稿 ID。
	 *
	 * @param draftUrl 当前写作页链接
	 * @return note 标识
	 */
	private String readNoteId(String draftUrl) {
		Matcher match = NOTE_ID.matcher(draftUrl);
		if (!match.find()) {
			throw new PublicationOutcomeUnknownException("简书草稿标识未确认，请核对草稿箱，勿直接重试");
		}
		return match.group(1);
	}

	/**
	 * 回读同一篇草稿，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * @param page     当前浏览器页面
	 * @param draftUrl 平台返回的草稿地址
	 * @param title    预期标题
	 * @param content  预期正文
	 */
	private void verifySavedDraft(Page page, String draftUrl, String title, String content) {
		page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		Locator titleInput = page.locator(TITLE_SELECTOR).first();
		Locator bodyInput = page.locator(BODY_SELECTOR).first();
		titleInput.waitFor();
		bodyInput.waitFor();
		String savedTitle = titleInput.inputValue();
		String savedContent = bodyInput.inputValue().stripTrailing();
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!title.equals(savedTitle) || !content.stripTrailing().equals(savedContent)) {
			throw new PublicationOutcomeUnknownException("简书草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台写作页链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
