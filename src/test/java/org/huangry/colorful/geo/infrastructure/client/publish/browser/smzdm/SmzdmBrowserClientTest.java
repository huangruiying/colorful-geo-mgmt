package org.huangry.colorful.geo.infrastructure.client.publish.browser.smzdm;

import com.microsoft.playwright.Request;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证什么值得买新文章编号读取、带正文自动保存的识别与保存结果解析。 */
class SmzdmBrowserClientTest {

    private static final String AUTO_SAVE_URL = "https://post.smzdm.com/api/editor/article/submit";

    /** 投稿页“发布新文章”链接带有新文章编号。 */
    @Test
    void 从新文章链接读取文章编号() {
        assertEquals("a70p7eko", SmzdmBrowserClient.readNewArticleId("/edit/a70p7eko"));
        assertEquals("ad7mel2d", SmzdmBrowserClient.readNewArticleId("https://post.smzdm.com/edit/ad7mel2d"));
    }

    /** 投稿页没有新文章链接时，明确说明不能投稿，不去猜编号。 */
    @Test
    void 没有新文章链接时拒绝() {
        assertThrows(PublicationClientException.class, () -> SmzdmBrowserClient.readNewArticleId(null));
        assertThrows(PublicationClientException.class, () -> SmzdmBrowserClient.readNewArticleId("/tougao/"));
    }

    /** 正文片段取首个非空行、去掉会被 HTML 转义的字符之后的部分，最多 20 字。 */
    @Test
    void 正文片段取首行未转义部分() {
        assertEquals("第一段", SmzdmBrowserClient.bodyProbe("\n\n第一段\n第二段"));
        assertEquals("价格 ", SmzdmBrowserClient.bodyProbe("价格 <100 元"));
        assertEquals("一二三四五六七八九十一二三四五六七八九十",
                SmzdmBrowserClient.bodyProbe("一二三四五六七八九十一二三四五六七八九十多出来的字"));
    }

    /** 只有带上正文的自动保存才算数；写标题时那次不含正文的自动保存要忽略。 */
    @Test
    void 只认带正文的自动保存() {
        Request titleOnly = autoSave("article_id=a1&submit_type=auto_save&title=标题&content=");
        Request withBody = autoSave("article_id=a1&submit_type=auto_save&title=标题&content=<p>第一段</p>");
        Request publish = autoSave("article_id=a1&submit_type=publish&content=<p>第一段</p>");

        assertFalse(SmzdmBrowserClient.isAutoSaveWithBody(titleOnly, "第一段"));
        assertTrue(SmzdmBrowserClient.isAutoSaveWithBody(withBody, "第一段"));
        assertFalse(SmzdmBrowserClient.isAutoSaveWithBody(publish, "第一段"));
    }

    /** 正文首行一开头就是转义字符时片段为空，此时只要正文字段不为空就算带正文。 */
    @Test
    void 片段为空时要求正文字段不为空() {
        assertFalse(SmzdmBrowserClient.isAutoSaveWithBody(
                autoSave("article_id=a1&submit_type=auto_save&title=标题&content="), ""));
        assertTrue(SmzdmBrowserClient.isAutoSaveWithBody(
                autoSave("article_id=a1&submit_type=auto_save&title=标题&content=<p>&lt;100</p>"), ""));
    }

    /** error_code 为 0 才是保存成功，否则带上平台原因。 */
    @Test
    void 自动保存失败时说明原因() {
        assertDoesNotThrow(() -> SmzdmBrowserClient.requireAutoSaveSucceeded("{\"error_code\":0,\"data\":{}}"));
        var exception = assertThrows(PublicationClientException.class,
                () -> SmzdmBrowserClient.requireAutoSaveSucceeded("{\"error_code\":1,\"error_msg\":\"标题过长\"}"));

        assertEquals("什么值得买草稿保存失败：标题过长", exception.getMessage());
    }

    /** 数据库没有登录记录时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new SmzdmBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", loginDao);
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));
        verifyNoInteractions(browser);
    }

    /**
     * 构造一条自动保存接口的 POST 请求，表单按浏览器方式 URL 编码。
     *
     * @param form 未编码的表单内容
     * @return 模拟请求
     */
    private Request autoSave(String form) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(AUTO_SAVE_URL);
        when(request.method()).thenReturn("POST");
        when(request.postData()).thenReturn(URLEncoder.encode(form, StandardCharsets.UTF_8)
                .replace("%3D", "=").replace("%26", "&"));
        return request;
    }
}
