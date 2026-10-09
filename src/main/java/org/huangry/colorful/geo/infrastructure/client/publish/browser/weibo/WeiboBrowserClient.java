package org.huangry.colorful.geo.infrastructure.client.publish.browser.weibo;

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
 * 使用数据库保存的微博登录态创建头条文章草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复微博 Playwright 会话
 * - 在微博“头条文章”编辑器写入标题与正文、点击“保存草稿”
 * - 回读标题与正文，确认平台已落库
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：微博短文用首页发博框，长文走独立“头条文章”编辑器（card.weibo.com）。
 * 本项目按文章草稿形态接入头条文章编辑器。正文为 TipTap/ProseMirror。
 * 以下选择器依据真实已登录会话探索获得（见 .doc/平台发文入口与选择器.md）。
 * 该编辑器为 hash 路由，草稿 id 可能不在 URL 中，故草稿 ID 可能为空；
 * 回读采用页内软校验，最终以草稿箱核对为准。失败一律按不确定结果处理，
 * 绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeiboBrowserClient {

	private static final String EDITOR_URL = "https://card.weibo.com/article/v5/editor#/draft";
	private static final Pattern DRAFT_ID = Pattern.compile("draft/(\\d+)|[?&]id=(\\d+)");

	private static final String TITLE_SELECTOR = "textarea[placeholder=\"请输入标题\"]";
	private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的微博登录态创建头条文章草稿，并从编辑器回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿 ID 和编辑链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("微博浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的微博 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("WEIBO");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("微博浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在微博头条文章编辑器写入内容并点击保存草稿，最后回读核验。
	 *
	 * <p>该编辑器为 hash 路由，草稿 id 是否进入 URL 待真实联调确认，无法可靠按 URL
	 * 重新打开同一篇草稿，此处做页内回读作为软校验，最终以草稿箱核对为准。</p>
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
			// 1. 打开已登录的微博头条文章编辑器；SPA 需等待标题与正文节点。
			page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			// 待真实联调：以下选择器依据真实已登录会话探索获得，需实测确认。
			Locator titleLocator = page.locator(TITLE_SELECTOR);
			Locator bodyLocator = page.locator(BODY_SELECTOR).first();
			titleLocator.waitFor();
			bodyLocator.waitFor();
			// 2. 标题为 textarea 可直接 fill；正文为 TipTap，需聚焦后用键盘输入。
			draftMayExist = true;
			titleLocator.fill(title);
			bodyLocator.click();
			page.keyboard().insertText(content);
			// 3. 点击保存草稿；等待保存落库（待真实联调：以“已保存”提示为准）。
			page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("保存草稿")).click();
			page.waitForTimeout(2000);
			String draftUrl = page.url();
			String draftId = readDraftId(draftUrl);
			verifySavedDraft(page, title, content);
			log.info("微博草稿已创建，remoteContentId={}", draftId);
			return new DraftCreated(draftId, draftUrl);
		} catch (PlaywrightException exception) {
			// 写入开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("微博草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("微博编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 从编辑页链接提取数字标识；无法确认时不伪造草稿 ID（可能为 null）。
	 *
	 * @param draftUrl 当前编辑页链接
	 * @return 草稿标识（可能为 null）
	 */
	private String readDraftId(String draftUrl) {
		Matcher match = DRAFT_ID.matcher(draftUrl);
		return match.find() ? match.group(1) : null;
	}

	/**
	 * 页内回读标题与正文，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * <p>微博头条文章编辑器为 hash 路由，此处仅在当前页面校验输入是否被保留，
	 * 最终是否真正落库以草稿箱核对为准。</p>
	 *
	 * @param page 当前浏览器页面
	 * @param title 预期标题
	 * @param content 预期正文
	 */
	private void verifySavedDraft(Page page, String title, String content) {
		String savedTitle = page.locator(TITLE_SELECTOR).inputValue();
		String savedBody = page.locator(BODY_SELECTOR).first().innerText().strip();
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!title.equals(savedTitle) || !content.strip().equals(savedBody)) {
			throw new PublicationOutcomeUnknownException("微博草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
