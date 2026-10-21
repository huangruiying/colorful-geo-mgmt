package org.huangry.colorful.geo.infrastructure.client.publish.browser.toutiao;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
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

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <pre>
 * 使用数据库保存的今日头条登录态创建文章草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复今日头条 Playwright 会话
 * - 在头条号编辑器写入标题与正文、等待自动保存
 * - 回读标题与正文，确认平台已落库
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：正文通过 ProseMirror 原生粘贴事件写入；自动保存接口返回有效 pgc_id 后，
 * 重新打开该草稿核验标题和正文。编辑器额外显示的段落间距不作为正文差异。
 * 失败一律按不确定结果处理，绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToutiaoBrowserClient {

	private static final String EDITOR_URL = "https://mp.toutiao.com/profile_v4/graphic/publish";
	private static final String SAVE_URL = "https://mp.toutiao.com/mp/agw/article/publish";

	private static final String TITLE_SELECTOR =
			"textarea[placeholder=\"请输入文章标题（2～30个字）\"]";
	private static final String BODY_SELECTOR = "div.ProseMirror";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的今日头条登录态创建草稿，并从编辑器回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿 ID 和编辑链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("今日头条浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的今日头条 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("TOUTIAO");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("今日头条浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在今日头条编辑器写入内容，等待自动保存，最后回读核验。
	 *
	 * <p>保存响应、有效草稿标识、重开回读必须同时满足；仅页面出现正文不代表保存成功。</p>
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
			// 1. 打开已登录的头条号编辑器；SPA 需等待标题与正文节点。
			page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			// 待真实联调：以下选择器依据真实已登录会话探索获得，需实测确认。
			Locator titleLocator = page.locator(TITLE_SELECTOR);
			Locator bodyLocator = page.locator(BODY_SELECTOR).first();
			titleLocator.waitFor();
			bodyLocator.waitFor();
			// 默认视口 1280×720 下编辑页会弹出「头条创作助手」抽屉＋全屏遮罩 byte-drawer-mask，
			// 遮罩拦截正文 click（fill 不受影响），须先关闭再操作，否则正文点击会超时误报。
			dismissAssistantDrawer(page);
			// 2. 在写入前监听自动保存；粘贴走编辑器原生事务，保留段落并触发保存。
			draftMayExist = true;
			Response saved = page.waitForResponse(response -> response.url().split("\\?")[0].equals(SAVE_URL)
					&& "POST".equals(response.request().method()),
					new Page.WaitForResponseOptions().setTimeout(properties.getTimeoutMillis()), () -> {
						titleLocator.fill(title);
						bodyLocator.evaluate("""
							(editor, text) => {
							  editor.focus();
							  const range = document.createRange();
							  range.selectNodeContents(editor);
							  const selection = window.getSelection();
							  selection.removeAllRanges(); selection.addRange(range);
							  const clipboard = new DataTransfer(); clipboard.setData('text/plain', text);
							  editor.dispatchEvent(new ClipboardEvent('paste', {
							    clipboardData: clipboard, bubbles: true, cancelable: true
							  }));
							}
							""", content);
					});
			// 3. 平台明确确认保存后才使用其标识；不伪造 ID 或把 HTTP 200 等同于业务成功。
			String draftId = requireSavedDraftId(saved.status(), saved.text());
			String draftUrl = EDITOR_URL + "?pgc_id=" + draftId;
			page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.waitForFunction("selector => document.querySelector(selector)?.value === "
					+ JSON.toJSONString(title), TITLE_SELECTOR);
			verifySavedDraft(page, title, content);
			log.info("今日头条草稿已创建，remoteContentId={}", draftId);
			return new DraftCreated(draftId, draftUrl);
		} catch (PlaywrightException exception) {
			// 写入开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("今日头条草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("今日头条编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 从草稿保存响应读取字符串标识；平台失败时保留待核对，不误报预发布成功。
	 *
	 * @param httpStatus HTTP 状态码
	 * @param responseBody 平台响应
	 * @return 已确认的草稿标识
	 */
	static String requireSavedDraftId(int httpStatus, String responseBody) {
		JSONObject result = JSON.parseObject(responseBody);
		JSONObject data = result == null ? null : result.getJSONObject("data");
		String id = data == null ? null : data.getString("pgc_id");
		if (httpStatus != 200 || result == null || !Integer.valueOf(0).equals(result.getInteger("code"))
				|| id == null || !id.matches("[1-9]\\d*")) {
			String code = result == null ? "未知" : result.getString("code");
			throw new PublicationOutcomeUnknownException("今日头条草稿保存未确认（平台返回码 " + code
					+ "），请核对草稿箱，勿直接重试");
		}
		return id;
	}

	/**
	 * 重开平台已保存的草稿后回读标题与正文，防止把本地编辑状态误报为落库成功。
	 *
	 * @param page 当前浏览器页面
	 * @param title 预期标题
	 * @param content 预期正文
	 */
	private void verifySavedDraft(Page page, String title, String content) {
		String savedTitle = page.locator(TITLE_SELECTOR).inputValue();
		String savedBody = page.locator(BODY_SELECTOR).first().innerText().strip();
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!title.equals(savedTitle) || !normalizeParagraphs(content).equals(normalizeParagraphs(savedBody))) {
			throw new PublicationOutcomeUnknownException("今日头条草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/**
	 * 按非空文本行比较富文本段落；只忽略展示空行，不忽略文字、顺序或行内空格。
	 *
	 * @param text 输入或回读正文
	 * @return 保留文本行边界的正文
	 */
	static String normalizeParagraphs(String text) {
		return Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').strip().split("\n", -1))
				.filter(line -> !line.isBlank()).collect(Collectors.joining("\n"));
	}

	/**
	 * 关闭今日头条编辑页自动弹出的「头条创作助手」抽屉及其全屏遮罩。
	 * 遮罩 {@code div.byte-drawer-mask} 会拦截正文 {@code click()}（{@code fill()} 不受影响），
	 * 必须在点正文之前关闭；优先点「我知道了」按钮，没有则点击遮罩本身使其消失。
	 *
	 * @param page 当前浏览器页面
	 */
	private void dismissAssistantDrawer(Page page) {
		try {
			Locator mask = page.locator("div.byte-drawer-mask");
			if (mask.count() == 0) {
				return;
			}
			Locator confirm = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("我知道了"));
			if (confirm.count() > 0) {
				confirm.first().click(new Locator.ClickOptions().setTimeout(5000));
			} else {
				mask.first().click(new Locator.ClickOptions().setTimeout(5000));
			}
			mask.first().waitFor(new Locator.WaitForOptions()
					.setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED).setTimeout(5000));
		} catch (PlaywrightException ignored) {
			// 浮层未出现或已消失；若正文 click 仍被拦截会在该步骤超时并如实报错。
		}
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
