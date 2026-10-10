package org.huangry.colorful.geo.infrastructure.client.publish.browser.weixin;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.springframework.test.util.ReflectionTestUtils;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/** 验证公众号预发布未取得数据库登录态时不触达浏览器。 */
class WeixinBrowserClientTest {

    /** 查看草稿只接受数字 ID；无登录态或非法标识不触达浏览器。 */
    @Test
    void 查看草稿校验标识与登录态() {
        var browser = mock(PlaywrightBrowserComponent.class);
        var client = new WeixinBrowserClient(mock(PlatformBrowserLoginDao.class), browser, new PublicationBrowserProperties());
        assertThrows(PublicationClientException.class, () -> client.readDraft("https://example.com/"));
        assertThrows(PublicationClientException.class, () -> client.readDraft("100000008"));
        verifyNoInteractions(browser);
    }

    /** 实际回读判定接受展示空行，但标题错误或正文丢字仍保持待核对。 */
    @Test
    void 回读必须核验标题与完整正文() {
        Page page = mock(Page.class);
        Locator title = mock(Locator.class), body = mock(Locator.class);
        when(page.locator(".ProseMirror[data-placeholder='请在这里输入标题']")).thenReturn(title);
        when(page.locator(".ProseMirror[contenteditable=true]")).thenReturn(body);
        when(body.last()).thenReturn(body);
        when(title.innerText()).thenReturn("标题");
        when(body.innerText()).thenReturn("第一段\n\n\n第二段\n");
        WeixinBrowserClient client = new WeixinBrowserClient(mock(PlatformBrowserLoginDao.class),
                mock(PlaywrightBrowserComponent.class), new PublicationBrowserProperties());

        ReflectionTestUtils.invokeMethod(client, "verifySavedDraft", page, "https://mp.weixin.qq.com/", "标题", "第一段\n\n第二段");
        verify(page).reload(any(Page.ReloadOptions.class));
        when(title.innerText()).thenReturn("其他标题");
        assertThrows(PublicationOutcomeUnknownException.class, () -> ReflectionTestUtils.invokeMethod(client,
                "verifySavedDraft", page, "https://mp.weixin.qq.com/", "标题", "第一段\n第二段"));
        when(title.innerText()).thenReturn("标题");
        when(body.innerText()).thenReturn("第一段\n第二");
        assertThrows(PublicationOutcomeUnknownException.class, () -> ReflectionTestUtils.invokeMethod(client,
                "verifySavedDraft", page, "https://mp.weixin.qq.com/", "标题", "第一段\n第二段"));
    }

    /** 公众号回读会增加段落空行，不能因此误报未保存。 */
    @Test
    void 展示空行与换行编码差异不影响正文核验() {
        assertEquals(WeixinBrowserClient.normalizeParagraphs("第一段\r\n\r\n第二段\r\n"),
                WeixinBrowserClient.normalizeParagraphs("第一段\n\n\n第二段\n\n"));
    }

    /** 删除文字或交换段落时必须仍然判为不一致。 */
    @Test
    void 丢字和顺序变化不能通过正文核验() {
        String expected = WeixinBrowserClient.normalizeParagraphs("第一段\n第二段");
        assertNotEquals(expected, WeixinBrowserClient.normalizeParagraphs("第一段\n第二"));
        assertNotEquals(expected, WeixinBrowserClient.normalizeParagraphs("第二段\n第一段"));
    }

    /** 不能为忽略展示空行而放宽到忽略全部空白或拼接所有文字。 */
    @Test
    void 非空行边界和行内空格必须保留() {
        assertNotEquals(WeixinBrowserClient.normalizeParagraphs("第一段\n第二段"),
                WeixinBrowserClient.normalizeParagraphs("第一段第二段"));
        assertNotEquals(WeixinBrowserClient.normalizeParagraphs("第一 段"),
                WeixinBrowserClient.normalizeParagraphs("第一段"));
    }

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
