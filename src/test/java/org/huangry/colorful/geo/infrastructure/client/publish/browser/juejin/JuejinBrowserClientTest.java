package org.huangry.colorful.geo.infrastructure.client.publish.browser.juejin;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证未保存或失效的掘金数据库登录态不会触发草稿创建。 */
class JuejinBrowserClientTest {

	/** 没有掘金登录记录时，不应启动浏览器。 */
	@Test
	void 未保存登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		JuejinBrowserClient client = new JuejinBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		PublicationClientException exception = assertThrows(PublicationClientException.class,
				() -> client.createDraft("标题", "正文"));

		assertEquals("掘金浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
		verifyNoInteractions(browser);
	}

	/** 已失效记录即使保留 storageState，也不能再次用于发布。 */
	@Test
	void 已失效登录态应拒绝创建草稿() {
		PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
		PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
		saved.setLoginStatus("EXPIRED");
		saved.setStorageState("{\"cookies\":[]}");
		when(loginDao.findByPlatform("JUEJIN")).thenReturn(saved);
		PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
		JuejinBrowserClient client = new JuejinBrowserClient(loginDao, browser, new PublicationBrowserProperties());

		assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));

		verifyNoInteractions(browser);
	}
}
