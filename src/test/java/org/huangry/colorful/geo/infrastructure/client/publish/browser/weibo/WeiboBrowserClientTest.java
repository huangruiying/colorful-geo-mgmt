package org.huangry.colorful.geo.infrastructure.client.publish.browser.weibo;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import java.util.function.Predicate;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证未保存或失效的微博数据库登录态不会触发草稿创建。 */
class WeiboBrowserClientTest {

	/** 展示空行不能导致误判，但丢字、顺序变化、合并文本行仍须失败。 */
	@Test
	void 正文比较仅忽略展示空行() {
		assertEquals(WeiboBrowserClient.normalizeParagraphs("第一段\n\n第二段"),
				WeiboBrowserClient.normalizeParagraphs("第一段\n\n\n第二段\n"));
		assertNotEquals(WeiboBrowserClient.normalizeParagraphs("第一段\n第二段"),
				WeiboBrowserClient.normalizeParagraphs("第一段第二段"));
		assertNotEquals(WeiboBrowserClient.normalizeParagraphs("第一 段"), WeiboBrowserClient.normalizeParagraphs("第一段"));
	}

	/** 仅平台明确确认成功时才能记录预发布成功。 */
	@Test
	void 保存响应必须同时满足HTTP与业务成功() {
		WeiboBrowserClient.requireSaveSuccess(200, "{\"code\":100000}");
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireSaveSuccess(200, "{\"code\":100001}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireSaveSuccess(500, "{\"code\":100000}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireSaveSuccess(200, "{}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireSaveSuccess(200, "not-json"));
	}

	/** 草稿 ID 来自创建响应而非历史路由，缺失或非法标识必须保持待核对。 */
	@Test
	void 必须使用创建响应的新草稿标识() {
		assertEquals("4082551", WeiboBrowserClient.requireCreatedDraftId(200, "{\"code\":100000,\"data\":{\"id\":\"4082551\"}}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireCreatedDraftId(200, "{\"code\":100000}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> WeiboBrowserClient.requireCreatedDraftId(200, "{\"code\":100000,\"data\":{\"id\":\"0\"}}"));
	}

	/** 先创建独立草稿、保存成功后重载核验，禁止只检查当前输入框。 */
	@Test
	@SuppressWarnings("unchecked")
	void 先点击写文章再保存并重开同一草稿() {
		PlatformBrowserLoginDao dao = mock(PlatformBrowserLoginDao.class);
		PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
		saved.setLoginStatus("LOGGED_IN");
		saved.setStorageState("{\"cookies\":[]}");
		when(dao.findByPlatform("WEIBO")).thenReturn(saved);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		PlaywrightBrowserComponent.BrowserSession session = mock(PlaywrightBrowserComponent.BrowserSession.class);
		Page page = mock(Page.class);
		Locator title = mock(Locator.class), body = mock(Locator.class), write = mock(Locator.class), save = mock(Locator.class);
		when(browser.openBrowser(any(Browser.NewContextOptions.class))).thenReturn(session);
		when(session.newPage()).thenReturn(page);
		when(page.locator("textarea[placeholder=\"请输入标题\"]")).thenReturn(title);
		when(page.locator("div.tiptap.ProseMirror")).thenReturn(body);
		when(body.first()).thenReturn(body);
		when(page.getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class)))
				.thenAnswer(invocation -> "写文章".equals(((Page.GetByRoleOptions) invocation.getArgument(1)).name) ? write : save);
		when(page.url()).thenReturn("https://card.weibo.com/article/v5/editor#/draft/4082549");
		Response response = mock(Response.class);
		Response created = mock(Response.class);
		when(created.status()).thenReturn(200);
		when(created.text()).thenReturn("{\"code\":100000,\"data\":{\"id\":\"4082549\"}}");
		when(response.status()).thenReturn(200);
		when(response.text()).thenReturn("{\"code\":100000}");
		int[] calls = {0};
		when(page.waitForResponse(any(Predicate.class), any(Runnable.class))).thenAnswer(invocation -> {
			invocation.getArgument(1, Runnable.class).run();
			return calls[0]++ == 0 ? created : response;
		});
		when(title.inputValue()).thenReturn("标题");
		when(body.innerText()).thenReturn("第一段\n\n\n第二段\n");

		WeiboBrowserClient.DraftCreated result = new WeiboBrowserClient(dao, browser, new PublicationBrowserProperties())
				.createDraft("标题", "第一段\n\n第二段");

		assertEquals("4082549", result.remoteContentId());
		assertEquals("https://card.weibo.com/article/v5/editor#/draft/4082549", result.draftUrl());
		var order = inOrder(write, body, title, save, page);
		order.verify(write).click();
		order.verify(body).click();
		order.verify(title).fill("标题");
		order.verify(save).click();
		order.verify(page).reload(any(Page.ReloadOptions.class));
		verify(session).close();
		verify(page, never()).waitForTimeout(anyDouble());
	}

	/** 没有微博登录记录时，不应启动浏览器。 */
	@Test
	void 未保存登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		WeiboBrowserClient client = new WeiboBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		PublicationClientException exception = assertThrows(PublicationClientException.class,
				() -> client.createDraft("标题", "正文"));

		assertEquals("微博浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
		verifyNoInteractions(browser);
	}

	/** 已失效记录即使保留 storageState，也不能再次用于发布。 */
	@Test
	void 已失效登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
		saved.setLoginStatus("EXPIRED");
		saved.setStorageState("{\"cookies\":[]}");
		when(loginDao.findByPlatform("WEIBO")).thenReturn(saved);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		WeiboBrowserClient client = new WeiboBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));

		verifyNoInteractions(browser);
	}
}
