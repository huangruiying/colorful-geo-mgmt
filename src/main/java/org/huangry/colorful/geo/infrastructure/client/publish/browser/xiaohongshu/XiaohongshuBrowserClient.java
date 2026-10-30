package org.huangry.colorful.geo.infrastructure.client.publish.browser.xiaohongshu;

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

import java.util.concurrent.locks.ReentrantLock;

/**
 * <pre>
 * 小红书写长文编辑器的浏览器客户端：用数据库登录态打开长文编辑器并写入标题、正文；入口为 createDraft。
 *
 * 当前未被调用（2026-10-30）：小红书草稿只存在浏览器本地，建草稿对用户不可见，预发布已改为只保存确认稿
 * （见 XiaohongshuPublicationStrategy 类注释）。保留本类供以后实现公开发布时复用“打开编辑器、写入内容”的步骤，
 * 届时去掉“暂存离开”，改为点击发布并确认结果。
 *
 * 负责：
 * - 从 platform_browser_login 恢复小红书 Playwright 会话
 * - 直接进入「写长文」发布页 →「新的创作」，打开长文编辑器
 * - 在标题 textarea 与正文 ProseMirror 写入内容，点击「暂存离开」存草稿
 * - 离开编辑器前在页内回读标题与正文，确认输入被保留
 *
 * 不负责：
 * - 公开发布（execPublish 由策略层另行接入，长文发布按钮待真实联调确认）
 * - 管理登录或账号凭据（凭据只来自数据库会话）
 *
 * 说明：小红书「上传图文」要求配图、无纯文本图文，「写长文」是唯一纯文本形态，
 * 故本项目按写长文接入长文草稿。以下选择器依据真实已登录会话探索获得
 * （见 .doc/平台发文入口与选择器.md）。
 *
 * 重要约束（真实联调结论）：
 * - 小红书创作后台是纯 SPA，长文草稿没有可寻址的单篇深链：编辑器 URL 不含草稿 id，
 *   页面无 {@code <a href>} 指向草稿，草稿 id 也无法在 headless 下通过接口响应可靠获取。
 *   因此 createDraft 返回的 {@code remoteContentId} 为 null，{@code draftUrl} 指向创作者中心首页
 *   （{@code CREATOR_HOME_URL}，其「草稿箱」卡片可进入草稿），"打开草稿"只能带到草稿箱入口，
 *   不能精确打开某一篇。
 * - 本方法仅校验「编辑器内仍显示刚输入的内容」，无法确认草稿已真正落库到服务端。
 *   实测在 headless 下点击「暂存离开」并未发出保存草稿的网络请求，存在伪成功风险；
 *   草稿是否真正进入草稿箱须以用户在真实浏览器中核对为准。
 * 失败一律按不确定结果处理，绝不自动重试或伪造草稿标识。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaohongshuBrowserClient {

	/**
	 * 「写长文」发布页入口。
	 *
	 * <p>新版创作中心首页（{@code /new/home}）的「发布笔记」已渲染为 div（非 {@code <button>}），
	 * 且发布页默认落在视频 tab（{@code target=video}），必须再切「写长文」tab 才出现「新的创作」。
	 * 多步点击既脆弱又易被遮挡，故直接深链到「写长文」tab，减少对首页/切 tab 的依赖。</p>
	 */
	private static final String ARTICLE_PUBLISH_URL =
			"https://creator.xiaohongshu.com/publish/publish?from=menu&target=article";
	/**
	 * 「打开草稿」的落点：小红书是纯 SPA，长文草稿没有可寻址的单篇深链（无 {@code <a href>}、
	 * 保存接口的草稿 id 也无法在 headless 下可靠获取），因此「打开草稿」只能带到创作者中心的
	 * 草稿箱入口，由用户在其中点开具体草稿。这里指向创作者中心首页（其「草稿箱」卡片可进入草稿）。
	 * 注意：{@code new/note-manager}（笔记管理）只含「已发布/审核中/未通过」，没有草稿 tab，
	 * 草稿不在笔记管理里，故不能指向那里。
	 */
	private static final String CREATOR_HOME_URL = "https://creator.xiaohongshu.com/new/home";
	private static final String TITLE_SELECTOR = "textarea[placeholder=\"输入标题\"]";
	private static final String BODY_SELECTOR = "div.tiptap.ProseMirror";

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的小红书登录态创建长文草稿，并在离开编辑器前回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿标识（可能为 null）和编辑器链接
	 */
	public DraftCreated createDraft(String title, String content) {
		String storageState = requireSavedStorageState();
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("小红书浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的小红书 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("XIAOHONGSHU");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("小红书浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在小红书长文编辑器写入内容并暂存离开，离开前页内回读核验。
	 *
	 * <p>长文草稿 id 是否进入编辑页 URL 待真实联调确认，此处取编辑器当前链接作为
	 * draftUrl，无法可靠提取时不伪造标识；正文是否真正落库以草稿箱核对为准。</p>
	 *
	 * @param storageState 数据库浏览器状态
	 * @param title 草稿标题
	 * @param content 草稿正文
	 * @return 已确认的草稿（标识可能为空）
	 */
	private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
		boolean draftMayExist = false;
		try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
				new Browser.NewContextOptions().setStorageState(storageState))) {
			Page page = session.newPage();
			page.setDefaultTimeout(properties.getTimeoutMillis());
			// 1. 直接进入「写长文」发布页（新版入口，见 ARTICLE_PUBLISH_URL 注释）。
			page.navigate(ARTICLE_PUBLISH_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			// 2. 点击「新的创作」打开长文编辑器（新版按钮仍为 button.new-btn）。
			page.locator("button.new-btn").first().click();
			// 3. 等待长文编辑器就绪：标题 textarea 与正文 ProseMirror。
			Locator titleLocator = page.locator(TITLE_SELECTOR);
			Locator bodyLocator = page.locator(BODY_SELECTOR).first();
			titleLocator.waitFor();
			bodyLocator.waitFor();
			// 写入开始前页面可能已落库，结果开始不确定，不能把后续异常当确定失败。
			draftMayExist = true;
			// 4. 标题为 textarea 可直接 fill；正文为 TipTap，需聚焦后用键盘输入。
			titleLocator.fill(title);
			bodyLocator.click();
			page.keyboard().insertText(content);
			// 5. 离开编辑器前在页内回读，确认输入被编辑器保留（暂存离开会导航离开）。
			verifySavedDraft(page, title, content);
			// 6. 显式暂存离开，触发平台保存并退出编辑器。
			page.locator("button:has-text('暂存离开')").click();
			page.waitForTimeout(1500);
			log.info("小红书长文草稿已创建，draftUrl={}", CREATOR_HOME_URL);
			// 小红书无单篇草稿深链，draftUrl 指向创作者中心草稿箱入口，remoteContentId 保持 null。
			// 注意：本方法仅校验「编辑器内仍显示刚输入的内容」，无法确认草稿已真正落库到服务端
			// （详见类注释）。若需强一致性，应以草稿箱实际出现该草稿为准。
			return new DraftCreated(null, CREATOR_HOME_URL);
		} catch (PlaywrightException exception) {
			// 写入开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("小红书草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("小红书编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 离开编辑器前在页内回读标题与正文，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * <p>长文编辑器点击「暂存离开」后会导航离开，无法在同一次会话重新打开同一篇草稿，
	 * 此处仅在当前页面校验输入是否被保留，最终是否真正落库以草稿箱核对为准。</p>
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
			throw new PublicationOutcomeUnknownException("小红书草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
		String expectedChunk = firstVisibleChunk(content);
		if (!expectedChunk.isEmpty() && !savedBody.contains(expectedChunk)) {
			throw new PublicationOutcomeUnknownException("小红书草稿正文未完整回读，请核对草稿箱，勿直接重试");
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

	/** @param remoteContentId 平台草稿标识（长文可能为 null） @param draftUrl 平台编辑器链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
