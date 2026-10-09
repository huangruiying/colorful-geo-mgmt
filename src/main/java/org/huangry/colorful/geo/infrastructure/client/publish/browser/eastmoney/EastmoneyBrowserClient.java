package org.huangry.colorful.geo.infrastructure.client.publish.browser.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * <pre>
 * 使用数据库保存的东方财富登录态创建草稿；入口为 createDraft。
 *
 * 负责：
 * - 从 platform_browser_login 恢复东方财富 Playwright 会话
 * - 打开东方财富创作平台（mp.eastmoney.com/collect/pc_article/index.html#/）
 * - 处理编辑器载入时的「您有一篇未编辑的文章，是否继续编辑？点击载入」旧草稿提示
 * - 在标题 input 与正文 ProseMirror 写入内容，点击「保存并预览」存草稿
 * - 保存后在页内回读标题与正文，确认输入被保留
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入，东方财富发布按钮待真实联调确认）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：以下选择器与保存时序均依据真实已登录会话探索获得（见 .doc/平台发文入口与选择器.md），
 * 并在沙箱外的真实 macOS 浏览器中完整跑通（填标题+正文→保存→草稿已保存→重载可恢复），
 * 非凭印象推断。东方财富编辑器是 hash 路由（编辑页 URL 不带草稿 id），但点击「保存并预览」时
 * 后端 SaveDraft 接口会返回平台草稿 id（draft_id，即"我的草稿"列表里每条草稿的平台 ID），
 * createDraft 通过监听 SaveDraft 响应把它取回填入 remoteContentId；若响应解析失败则退化为 null，
 * 以草稿箱核对为准。失败一律按不确定结果处理，绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EastmoneyBrowserClient {

	private static final String CREATOR_URL = "https://mp.eastmoney.com/collect/pc_article/index.html#/";
	private static final String TITLE_SELECTOR = "input[placeholder=\"标题(1-64字)\"]";
	private static final String BODY_SELECTOR = "div.ProseMirror.cfh_editor_area";
	// 东方财富「保存并预览」实际渲染为 <div class="button_preview item editor-btn editor-default-btn">，
	// 不是 <button>，因此只能用文本定位，禁止写成 button:has-text(...)（会永远命中 0 个元素并超时）。
	private static final String SAVE_DRAFT_BUTTON = "保存并预览";
	// 东方财富建稿走通用网关 /apifront/Tran/GetData；真正的业务接口 path 写在请求体里。
	private static final String TRAN_GET_DATA_PATH = "/apifront/Tran/GetData";
	// SaveDraft 业务 path（位于 Tran/GetData 请求体的 "path" 字段，不在 URL 上）；响应 RData 内返回 draft_id。
	private static final String SAVE_DRAFT_API_PATH = "draft/api/Article/SaveDraft";
	private static final String STALE_DRAFT_PROMPT = "您有一篇未编辑的文章";
	private static final String LOAD_DRAFT_BUTTON = "点击载入";
	private static final String SAVED_MARKER = "草稿已保存";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ObjectMapper objectMapper;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的东方财富登录态创建草稿，并在保存后页内回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿标识（可能为 null）和编辑器链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("东方财富浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的东方财富 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("EASTMONEY");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("东方财富浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在东方财富创作平台写入内容并保存草稿，保存后页内回读核验。
	 *
	 * <p>东方财富为单活跃草稿模型：编辑器载入时若存在旧草稿，会弹出
	 * 「您有一篇未编辑的文章，是否继续编辑？点击载入」提示。这里先点「点击载入」把旧草稿载回，
	 * 再清空标题与正文、写入新内容，避免把内容写进旧草稿的残留文本之后。</p>
	 *
	 * <p>正文为 ProseMirror 富文本，需聚焦后用键盘全选删除清空、再用 insertText 写入；
	 * 标题为普通 input 可直接 fill。保存按钮为「保存并预览」，点击后页面出现「草稿已保存」标记，
	 * 且正文仍在编辑器内，可在同一次会话回读确认。</p>
	 *
	 * <p>东方财富编辑页是 hash 路由（URL 不带草稿 id），但「保存并预览」调用的 SaveDraft 接口会在
	 * 响应 RData 中返回 draft_id（即"我的草稿"列表里该草稿的平台 ID）。这里在保存前注册响应监听，
	 * 从 SaveDraft 响应里取回 draft_id 填入 remoteContentId；解析失败则退化为 null，以草稿箱核对为准。</p>
	 *
	 * @param storageState 数据库浏览器状态
	 * @param title 草稿标题
	 * @param content 草稿正文
	 * @return 已确认的草稿（标识取自 SaveDraft 响应，可能为空）
	 */
	private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
		boolean draftMayExist = false;
		try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
				new Browser.NewContextOptions().setStorageState(storageState))) {
		Page page = session.newPage();
		page.setDefaultTimeout(properties.getTimeoutMillis());
		// 保存前注册响应监听：从 SaveDraft 接口响应中取回平台草稿 id（draft_id）。
		// 东方财富编辑页是 hash 路由（URL 不带草稿 id），但"我的草稿"列表里每条草稿的平台 ID
		// 正来自该接口的 draft_id 字段，必须在此处抓取，不能依赖 URL。
		AtomicReference<String> capturedDraftId = new AtomicReference<>();
		page.onResponse(response -> captureDraftId(response, capturedDraftId));
		// 1. 打开东方财富创作平台。
		page.navigate(CREATOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		Locator titleLocator = page.locator(TITLE_SELECTOR);
		Locator bodyLocator = page.locator(BODY_SELECTOR).first();
		titleLocator.waitFor();
			bodyLocator.waitFor();
			// 2. 处理旧草稿提示：存在则先「点击载入」把旧草稿载回，便于随后清空重写。
			Boolean promptShown = (Boolean) page.evaluate(
					"() => (document.body.innerText || '').includes('您有一篇未编辑的文章')");
			if (Boolean.TRUE.equals(promptShown)) {
				page.getByText(LOAD_DRAFT_BUTTON, new Page.GetByTextOptions().setExact(true)).first().click();
				page.waitForTimeout(3000);
			}
			// 3. 清空标题与旧正文（标题 fill 整体替换；正文为富文本，全选后删除）。
			titleLocator.fill("");
			bodyLocator.click();
			page.keyboard().press("Meta+A");
			page.keyboard().press("Delete");
			page.waitForTimeout(500);
			// 4. 写入新标题与正文；正文聚焦后用键盘输入。
			titleLocator.fill(title);
			bodyLocator.click();
			page.keyboard().insertText(content);
			// 5. 写入开始后平台可能已自动存稿，结果开始不确定，不能把后续异常当确定失败。
			draftMayExist = true;
			String editorUrl = page.url();
			// 6. 点击「保存并预览」，等待「草稿已保存」确认已落库。
			// 该按钮是 div 而非 button，必须用文本定位（见 SAVE_DRAFT_BUTTON 注释）。
			page.getByText(SAVE_DRAFT_BUTTON, new Page.GetByTextOptions().setExact(true)).first().click();
			// Playwright Java 没有 waitForFunction(expr, options) 重载：第 2 个参数是传给 JS 的 arg，
			// 直接把 WaitForFunctionOptions 传进去会被当作参数序列化并抛
			// "Unsupported type of argument"。必须用 (expr, arg, options) 三参数形式。
			page.waitForFunction(
					"() => (document.body.innerText || '').includes('草稿已保存')",
					null,
					new Page.WaitForFunctionOptions().setTimeout(properties.getTimeoutMillis()));
		// 7. 保存后页内回读，确认输入被保留（与草稿箱核对互为印证）。
		verifySavedDraft(page, title, content);
		// 8. 等待 SaveDraft 响应落地（"草稿已保存"出现即代表响应已返回），取回平台草稿 id。
		page.waitForTimeout(800);
		String draftId = capturedDraftId.get();
		if (draftId == null || draftId.isBlank()) {
			log.warn("东方财富SaveDraft响应未取到草稿ID，remoteContentId将置为null，请以草稿箱核对");
		}
		log.info("东方财富草稿已创建，draftId={}, draftUrl={}", draftId, editorUrl);
		return new DraftCreated(draftId, editorUrl);
		} catch (PlaywrightException exception) {
			// 写入/保存开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("东方财富草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("东方财富编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 保存后页内回读标题与正文，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * <p>「保存并预览」不会导航离开编辑器，可在同一次会话校验输入是否被保留；
	 * 最终是否真正落库以草稿箱核对为准（重载编辑器会出现「您有一篇未编辑的文章」提示，点「点击载入」可恢复）。</p>
	 *
	 * @param page 当前编辑器页面
	 * @param title 预期标题
	 * @param content 预期正文
	 */
	private void verifySavedDraft(Page page, String title, String content) {
		String savedTitle = page.locator(TITLE_SELECTOR).inputValue();
		String savedBody = page.locator(BODY_SELECTOR).first().innerText().strip();
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!title.equals(savedTitle) || savedBody.isBlank()) {
			throw new PublicationOutcomeUnknownException("东方财富草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
		String expectedChunk = firstVisibleChunk(content);
		if (!expectedChunk.isEmpty() && !savedBody.contains(expectedChunk)) {
			throw new PublicationOutcomeUnknownException("东方财富草稿正文未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/**
	 * 从正文提取一段可见文本片段，用于回读时识别写入成功而非逐字比较富文本。
	 *
	 * @param content 原始正文
	 * @return 首段可见文本片段（最长 15 字）；纯图片正文返回空串
	 */
	private static String firstVisibleChunk(String content) {
		return content.lines().map(String::strip)
				.filter(line -> !line.isBlank())
				.map(line -> line.replaceFirst("^#{1,6}\\s*", "")
						.replaceFirst("^([-*+]|[0-9]+\\.)\\s*", "")
						.replaceAll("`", "").strip())
				.filter(line -> !line.isBlank())
				.map(line -> line.length() > 15 ? line.substring(0, 15) : line)
				.findFirst().orElse("");
	}

	/** @param remoteContentId 平台草稿标识（来自 SaveDraft 响应的 draft_id，取不到时为 null） @param draftUrl 平台编辑器链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }

	/**
	 * 从 SaveDraft 接口响应中解析平台草稿 id（draft_id）。
	 *
	 * <p>「保存并预览」会触发 {@code POST .../apifront/Tran/GetData}（东方财富的通用网关，真正的业务接口
	 * path 写在请求体 JSON 的 {@code "path":"draft/api/Article/SaveDraft"} 字段里，不在 URL 上），其响应形如
	 * {@code {"RData":"{\"error_code\":0,\"draft_id\":\"<id>\",...}","RCode":200}}——RData 是二次编码的 JSON
	 * 字符串，需拆两层才能取到 draft_id。该 id 即为"我的草稿"列表里该草稿的平台 ID。解析失败（非 SaveDraft、
	 * 响应不可读、字段缺失）时静默忽略，由调用方退化为 null，绝不以伪造值填充。</p>
	 *
	 * @param response 任意接口响应
	 * @param draftIdRef 命中 SaveDraft 时回写草稿 id 的引用
	 */
	private void captureDraftId(Response response, AtomicReference<String> draftIdRef) {
		try {
			if (!response.url().contains(TRAN_GET_DATA_PATH)
					|| !"POST".equalsIgnoreCase(response.request().method())) {
				return;
			}
			// 通用网关 URL 不含业务 path；真正的接口 path 在请求体里，据此判断是否 SaveDraft。
			String requestBody = response.request().postData();
			if (requestBody == null || !requestBody.contains(SAVE_DRAFT_API_PATH)) {
				return;
			}
			String body = response.text();
			JsonNode root = objectMapper.readTree(body);
			JsonNode rdata = root.path("RData");
			if (rdata.isMissingNode() || !rdata.isTextual()) {
				return;
			}
			JsonNode inner = objectMapper.readTree(rdata.asText());
			JsonNode draftId = inner.path("draft_id");
			if (draftId.isTextual() && !draftId.asText().isBlank()) {
				draftIdRef.set(draftId.asText());
				log.info("东方财富SaveDraft响应返回草稿ID={}", draftId.asText());
			}
		} catch (Exception exception) {
			log.warn("解析东方财富SaveDraft响应草稿ID失败: {}", exception.getMessage());
		}
	}
}
