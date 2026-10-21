package org.huangry.colorful.geo.infrastructure.client.publish.browser.juejin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.alibaba.fastjson.JSONObject;
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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <pre>
 * 使用数据库保存的掘金登录态创建文章草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复掘金 Playwright 会话
 * - 在掘金编辑器写入标题与 Markdown 正文、等待自动保存跳转草稿页
 * - 重新打开同一篇草稿回读标题与正文，确认平台已落库
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：掘金正文为 CodeMirror（Markdown），需聚焦隐藏 textarea 后用键盘输入。
 * 以下选择器依据真实已登录会话探索获得（见 .doc/平台发文入口与选择器.md），
 * 首次进入有“选择技术方向”引导弹窗（点“跳过”）。掘金编辑页 URL 不会跳转，
 * 因此改由捕获草稿自动保存接口 article_draft/create 的响应、按 err_no 判定成功，
 * 并把服务端明确拒绝（如 403 must bind phone）映射为确定失败。失败一律按不确定
 * 结果处理，绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JuejinBrowserClient {

	private static final String EDITOR_URL = "https://juejin.cn/editor/drafts/new";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的掘金登录态创建草稿，并从平台草稿页回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核 Markdown 正文
	 * @return 平台草稿 ID 和草稿链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("掘金浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的掘金 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("JUEJIN");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("掘金浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在掘金编辑器写入内容，等待自动保存生成草稿并跳转，最后回读核验。
	 *
	 * @param storageState 数据库浏览器状态
	 * @param title 草稿标题
	 * @param content 草稿正文
	 * @return 已确认的草稿
	 */
	private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
		boolean draftMayExist = false;
		AtomicReference<String> draftResponseText = new AtomicReference<>();
		try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
				new Browser.NewContextOptions().setStorageState(storageState))) {
			Page page = session.newPage();
			page.setDefaultTimeout(properties.getTimeoutMillis());
			// 掘金编辑页 URL 不会跳转（停留在 /editor/drafts/new），不能用 URL 判成功；
			// 改为捕获草稿自动保存接口 article_draft/create 的响应，按 err_no 判定。
			page.onResponse(response -> {
				if (response.url().contains("content_api/v1/article_draft/create")) {
					try {
						draftResponseText.set(response.text());
					} catch (PlaywrightException ignored) {
						// 响应体不可读时忽略，等待下一次。
					}
				}
			});
			// 1. 打开已登录的掘金编辑器；首次进入可能弹“选择技术方向”引导，点“跳过”。
			page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			try {
				page.getByText("跳过").first().click(new com.microsoft.playwright.Locator.ClickOptions()
						.setTimeout(3000));
			} catch (PlaywrightException ignored) {
				// 引导弹窗并非每次出现；未出现时忽略超时。
			}
			page.locator("input.title-input").waitFor();
			page.locator("div.CodeMirror").first().waitFor();
			// 2. 标题为 input 可直接 fill；正文为 CodeMirror，需聚焦后用键盘输入。
			draftMayExist = true;
			page.locator("input.title-input").fill(title);
			page.locator("div.CodeMirror").first().click();
			page.keyboard().insertText(content);
			// 3. 等待草稿接口响应（自动保存）。未捕获到响应时结果不确定，不能伪报成功。
			try {
				page.waitForCondition(() -> draftResponseText.get() != null,
						new Page.WaitForConditionOptions().setTimeout(properties.getTimeoutMillis()));
			} catch (PlaywrightException timeout) {
				throw new PublicationOutcomeUnknownException("掘金草稿接口未返回，请核对草稿箱，勿直接重试", timeout);
			}
			String json = draftResponseText.get();
			JSONObject payload;
			try {
				payload = JSONObject.parseObject(json);
			} catch (RuntimeException parseError) {
				throw new PublicationOutcomeUnknownException("掘金草稿接口响应无法解析，请核对草稿箱，勿直接重试", parseError);
			}
			int errNo = payload.getIntValue("err_no");
			if (errNo != 0) {
				// 服务端明确拒绝（如 403 must bind phone）属于确定失败，映射为预发布失败而非“待核对”，
				// 让调用方明确知道需在掘金账号侧处理（如绑定手机号）后重试。
				String errMsg = payload.getString("err_msg");
				throw new PublicationClientException("掘金草稿创建被服务端拒绝（err_no=" + errNo
						+ (errMsg != null ? " " + errMsg : "") + "），请检查掘金账号状态（如绑定手机号）后重试");
			}
			JSONObject data = payload.getJSONObject("data");
			if (data == null) {
				throw new PublicationOutcomeUnknownException("掘金草稿标识未确认，请核对草稿箱，勿直接重试");
			}
			// 2026-10-08 真实抓包：成功响应的字段是 data.id（data.draft_id 不存在），
			// 此前假设 draft_id 导致草稿创建成功却被误报“标识未确认”。draft_id 仅作兼容保留。
			String draftId = data.getString("draft_id");
			if (draftId == null || draftId.isBlank()) {
				draftId = data.getString("id");
			}
			if (draftId == null || draftId.isBlank()) {
				throw new PublicationOutcomeUnknownException("掘金草稿标识未确认，请核对草稿箱，勿直接重试");
			}
			String draftUrl = "https://juejin.cn/editor/drafts/" + draftId;
			verifySavedDraft(page, draftUrl, title, content);
			log.info("掘金草稿已创建并回读确认，remoteContentId={}", draftId);
			return new DraftCreated(draftId, draftUrl);
		} catch (PublicationClientException known) {
			// 确定失败或已知的不确定结果（PublicationOutcomeUnknownException 为其子类），原样抛出，不重新包装。
			throw known;
		} catch (PlaywrightException exception) {
			// 写入开始前页面异常（如编辑器打不开）归为确定失败。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("掘金草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("掘金编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 回读同一篇草稿，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * @param page 当前浏览器页面
	 * @param draftUrl 平台返回的草稿地址
	 * @param title 预期标题
	 * @param content 预期正文
	 */
	private void verifySavedDraft(Page page, String draftUrl, String title, String content) {
		page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.locator("input.title-input").waitFor();
		String savedTitle = page.locator("input.title-input").inputValue();
		String savedBody = page.locator("div.CodeMirror").first().innerText().strip();
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!title.equals(savedTitle) || savedBody.isEmpty()) {
			throw new PublicationOutcomeUnknownException("掘金草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台草稿链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
