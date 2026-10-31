package org.huangry.colorful.geo.infrastructure.client.publish.browser.imooc;

import com.microsoft.playwright.Request;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证慕课网自动保存的识别、保存结果解析与登录态边界。 */
class ImoocBrowserClientTest {

    /** 保存接口 result 为 0 且 data 为正数时取得草稿 ID。 */
    @Test
    void 保存成功时返回草稿ID() {
        assertEquals("208445", ImoocBrowserClient.readSavedDraftId("{\"result\":0,\"data\":208445,\"msg\":\"成功\"}"));
    }

    /** 平台明确拒绝时带上平台原因。 */
    @Test
    void 平台拒绝保存时说明原因() {
        var exception = assertThrows(PublicationClientException.class,
                () -> ImoocBrowserClient.readSavedDraftId("{\"result\":1,\"msg\":\"内容过长\"}"));

        assertEquals("慕课网草稿保存失败：内容过长", exception.getMessage());
    }

    /** 保存成功但没有草稿 ID 时结果未确认。 */
    @Test
    void 缺少草稿ID时结果未确认() {
        assertInstanceOf(PublicationOutcomeUnknownException.class, assertThrows(PublicationClientException.class,
                () -> ImoocBrowserClient.readSavedDraftId("{\"result\":0}")));
    }

    /** 只认 Markdown 编辑器（editor=0）且带上正文的保存；写标题时空正文的那次要忽略。 */
    @Test
    void 只认带正文的Markdown自动保存() {
        assertFalse(ImoocBrowserClient.isSaveWithBody(save("editor=0&content=&title=标题&draft_id="), "# 小标题"));
        assertTrue(ImoocBrowserClient.isSaveWithBody(save("editor=0&content=# 小标题\n\n正文&title=标题&draft_id="), "# 小标题"));
        assertFalse(ImoocBrowserClient.isSaveWithBody(save("editor=1&content=# 小标题&title=标题&draft_id="), "# 小标题"));
    }

    /** 正文片段取首个非空行，最多 20 字。 */
    @Test
    void 正文片段取首个非空行() {
        assertEquals("# 小标题", ImoocBrowserClient.bodyProbe("\n\n# 小标题\n正文"));
    }

    /** 数据库没有登录记录时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new ImoocBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", mock(PlatformBrowserLoginDao.class));
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));
        verifyNoInteractions(browser);
    }

    /**
     * 构造一条保存接口的 POST 请求，表单按浏览器方式 URL 编码（空格编成 +）。
     *
     * @param form 未编码的表单内容
     * @return 模拟请求
     */
    private Request save(String form) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn("https://www.imooc.com/article/savedraft");
        when(request.method()).thenReturn("POST");
        when(request.postData()).thenReturn(URLEncoder.encode(form, StandardCharsets.UTF_8)
                .replace("%3D", "=").replace("%26", "&"));
        return request;
    }
}
