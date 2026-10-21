package org.huangry.colorful.geo.infrastructure.client.publish.browser.weibo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
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

import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * <pre>
 * 使用数据库保存的微博登录态创建头条文章草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复微博 Playwright 会话
 * - 点击“写文章”创建草稿，再写入标题与正文、点击“保存草稿”
 * - 回读标题与正文，确认平台已落库
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：微博短文用首页发博框，长文走独立“头条文章”编辑器（card.weibo.com）。
 * 本项目按文章草稿形态接入头条文章编辑器。正文为 TipTap/ProseMirror。
 * 以下选择器依据真实已登录会话探索获得（见 .doc/平台发文入口与选择器.md）。
 * 保存必须返回成功码 100000，并按 hash 路由中的草稿 ID 重开核验；
 * 创建请求发出后发生异常时保持结果待核对，不自动重试。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeiboBrowserClient {

	private static final String EDITOR_URL = "https://card.weibo.com/article/v5/editor#/draft";

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
	 * 创建新文章后写入内容，等待平台保存成功并重开同一草稿核验。
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
			// 1. 草稿箱默认展示不可编辑的空壳；先点击左侧“写文章”创建独立草稿。
			page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			draftMayExist = true;
			Response created = page.waitForResponse(response -> response.url().contains("/article/v5/aj/editor/draft/create")
					&& "POST".equals(response.request().method()), () -> page.getByRole(AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("写文章").setExact(true)).click());
			String draftId = requireCreatedDraftId(created.status(), created.text());
			// 草稿箱会自动选中历史草稿；必须等待创建响应里的新 ID，不能取当前旧路由。
			page.waitForURL(EDITOR_URL + "/" + draftId);
			// 2. 正文使用编辑器原生粘贴事务，保留多段正文的文本行结构。
			Locator titleLocator = page.locator(TITLE_SELECTOR);
			Locator bodyLocator = page.locator(BODY_SELECTOR).first();
			titleLocator.waitFor();
			bodyLocator.waitFor();
			// 标题框先于正文加载完成；先等待正文可点击，避免后台回填空标题覆盖本次输入。
			bodyLocator.click();
			titleLocator.fill(title);
			bodyLocator.focus();
			bodyLocator.evaluate("(element, text) => { const data = new DataTransfer(); "
					+ "data.setData('text/plain', text); element.dispatchEvent(new ClipboardEvent('paste', "
					+ "{clipboardData: data, bubbles: true, cancelable: true})); }", content);
			// 3. HTTP 成功并不代表落库；等待本草稿的保存响应，再验证平台业务码。
			Response saved = page.waitForResponse(response -> response.url().contains("/article/v5/aj/editor/draft/save")
					&& "POST".equals(response.request().method()), () -> page.getByRole(AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("保存草稿").setExact(true)).click());
			requireSaveSuccess(saved.status(), saved.text());
			// 4. 重开平台已落库的草稿，不能用当前编辑器里的本地输入充当成功证据。
			String draftUrl = EDITOR_URL + "/" + draftId;
			page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			page.locator(TITLE_SELECTOR).waitFor();
			page.waitForFunction("expected => document.querySelector('textarea[placeholder=\"请输入标题\"]')?.value === expected", title);
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
	 * 回读重新加载的草稿标题与正文，仅忽略富文本的展示空行。
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
			throw new PublicationOutcomeUnknownException("微博草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/** 校验保存业务码；缺失、拒绝或无法解析的响应均不能标记预发布成功。 */
	static void requireSaveSuccess(int httpStatus, String responseBody) {
		try {
			JSONObject result = JSON.parseObject(responseBody);
			// 微博成功码为 100000；HTTP 200 或页面仍保留输入不代表保存成功。
			if (httpStatus != 200 || result == null || !Integer.valueOf(100000).equals(result.getInteger("code"))) {
				throw new PublicationOutcomeUnknownException("微博草稿保存未确认，请核对草稿箱，勿直接重试");
			}
		} catch (JSONException exception) {
			throw new PublicationOutcomeUnknownException("微博草稿保存响应无法解析，请核对草稿箱，勿直接重试", exception);
		}
	}

	/** 从创建响应读取新草稿 ID，防止把草稿箱自动选中的历史草稿当成新稿。 */
	static String requireCreatedDraftId(int httpStatus, String responseBody) {
		requireSaveSuccess(httpStatus, responseBody);
		JSONObject data = JSON.parseObject(responseBody).getJSONObject("data");
		String draftId = data == null ? null : data.getString("id");
		// 只有本次创建返回的有效标识才能作为后续编辑与保存的目标。
		if (draftId == null || !draftId.matches("[1-9]\\d*")) {
			throw new PublicationOutcomeUnknownException("微博新草稿标识未确认，请核对草稿箱，勿直接重试");
		}
		return draftId;
	}

	/** 忽略富文本展示空行，仍保留非空行的文字、顺序和行内空格。 */
	static String normalizeParagraphs(String content) {
		return content.replace("\r\n", "\n").lines().filter(line -> !line.isBlank()).collect(Collectors.joining("\n"));
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
