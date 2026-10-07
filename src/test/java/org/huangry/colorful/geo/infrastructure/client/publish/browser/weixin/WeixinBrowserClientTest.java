package org.huangry.colorful.geo.infrastructure.client.publish.browser.weixin;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证公众号预发布未取得数据库登录态时不触达浏览器。 */
class WeixinBrowserClientTest {

    /** 没有已保存公众号会话时，不得创建草稿。 */
    @Test
    void 未保存公众号登录态应拒绝创建草稿() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        WeixinBrowserClient client = new WeixinBrowserClient(loginDao, browser, new PublicationBrowserProperties());

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> client.createDraft("标题", "正文"));

        assertEquals("微信公众号浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
        verifyNoInteractions(browser);
    }
}
