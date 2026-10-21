package org.huangry.colorful.geo.infrastructure.client.publish.browser.toutiao;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证未保存或失效的今日头条数据库登录态不会触发草稿创建。 */
class ToutiaoBrowserClientTest {

	/** 富文本展示空行可以不同，但正文文字与段落顺序不能改变。 */
	@Test
	void 回读应忽略展示空行但保留内容边界() {
		assertEquals(ToutiaoBrowserClient.normalizeParagraphs("第一段\n\n第二段"),
				ToutiaoBrowserClient.normalizeParagraphs("第一段\n\n\n第二段\n\n"));
		assertNotEquals(ToutiaoBrowserClient.normalizeParagraphs("第一段\n第二段"),
				ToutiaoBrowserClient.normalizeParagraphs("第一段第二段"));
	}

	/** HTTP 成功不等于平台保存成功，7050 及无标识响应均不能推进为预发布成功。 */
	@Test
	void 平台保存失败不得返回成功() {
		assertThrows(PublicationOutcomeUnknownException.class, () -> ToutiaoBrowserClient.requireSavedDraftId(200,
				"{\"code\":7050,\"data\":{\"pgc_id\":\"0\"}}"));
		assertThrows(PublicationOutcomeUnknownException.class, () -> ToutiaoBrowserClient.requireSavedDraftId(200,
				"{\"code\":0,\"data\":{\"pgc_id\":\"0\"}}"));
		assertEquals("7694244553213166134", ToutiaoBrowserClient.requireSavedDraftId(200,
				"{\"code\":0,\"data\":{\"pgc_id\":\"7694244553213166134\"}}"));
	}

	/** 没有今日头条登录记录时，不应启动浏览器。 */
	@Test
	void 未保存登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		ToutiaoBrowserClient client = new ToutiaoBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		PublicationClientException exception = assertThrows(PublicationClientException.class,
				() -> client.createDraft("标题", "正文"));

		assertEquals("今日头条浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
		verifyNoInteractions(browser);
	}

	/** 已失效记录即使保留 storageState，也不能再次用于发布。 */
	@Test
	void 已失效登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
		saved.setLoginStatus("EXPIRED");
		saved.setStorageState("{\"cookies\":[]}");
		when(loginDao.findByPlatform("TOUTIAO")).thenReturn(saved);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		ToutiaoBrowserClient client = new ToutiaoBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));

		verifyNoInteractions(browser);
	}
}
