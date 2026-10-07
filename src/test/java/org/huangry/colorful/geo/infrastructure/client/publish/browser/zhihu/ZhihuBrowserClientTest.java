package org.huangry.colorful.geo.infrastructure.client.publish.browser.zhihu;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知乎浏览器客户端的前置边界测试，覆盖草稿创建与公开发布。
 *
 * <p>仅验证输入与数据库会话，不启动浏览器，也不触发平台请求。</p>
 *
 * @author huangry
 */
class ZhihuBrowserClientTest {

    /** 非数字标识不能被拼入浏览器导航地址。 */
    @Test
    void 非法草稿标识应在启动浏览器前拒绝() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> client.publishDraft("123/../../other"));

        assertEquals("知乎草稿标识必须为数字", exception.getMessage());
    }

    /** 数据库没有知乎登录态时，不得打开草稿发布页面。 */
    @Test
    void 未保存知乎登录态应在启动浏览器前拒绝() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> client.publishDraft("123456"));

        assertEquals("知乎浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
    }

    /** 已保存的会话状态失效时，不得继续公开发布。 */
    @Test
    void 已失效的知乎登录态应在启动浏览器前拒绝() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus("EXPIRED");
        saved.setStorageState("{\"cookies\":[]}");
        when(loginDao.findByPlatform("ZHIHU")).thenReturn(saved);
        ZhihuBrowserClient client = client(loginDao);

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> client.publishDraft("123456"));

        assertEquals("知乎浏览器未登录或登录态不可用，请先在浏览器登录管理中登录", exception.getMessage());
    }

    /** 远程 Markdown 图片和 HTML 图片都纳入回读数量校验。 */
    @Test
    void 应统计远程图片() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));
        String markdown = "正文 ![图片](https://example.com/a.png)\n"
                + "<img src=\"https://example.com/b.jpg\" alt=\"图片\">";

        assertEquals(2, client.countSupportedImages("测试标题", markdown));
    }

    /** 本地图片无法由知乎 MD 导入保真，必须在创建草稿前拒绝。 */
    @Test
    void 应拒绝本地图片() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        assertThrows(PublicationClientException.class,
                () -> client.countSupportedImages("测试标题", "![图片](/tmp/a.png)"));
    }

    /** 内嵌图片在实测导入中未生成图片节点，不能误报成功。 */
    @Test
    void 应拒绝内嵌图片() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        assertThrows(PublicationClientException.class,
                () -> client.countSupportedImages("测试标题", "![图片](data:image/png;base64,abc)"));
    }

    /** 图片语法无法识别时，不能默默作为普通文字导入。 */
    @Test
    void 应拒绝不完整图片语法() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        assertThrows(PublicationClientException.class,
                () -> client.countSupportedImages("测试标题", "内容 ![图片]"));
    }

    /** 知乎编辑器最多接受 100 字标题，不能静默截断。 */
    @Test
    void 应拒绝超长标题() {
        ZhihuBrowserClient client = client(mock(PlatformBrowserLoginDao.class));

        assertThrows(PublicationClientException.class,
                () -> client.countSupportedImages("标题".repeat(51), "正文"));
    }

    /** 数据库没有可用登录态时，不创建临时文件或访问知乎。 */
    @Test
    void 缺少知乎登录态时不启动浏览器() {
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        ZhihuBrowserClient client = new ZhihuBrowserClient(new PublicationBrowserProperties(),
                new PlaywrightBrowserComponent(), loginDao);

        assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));
        verify(loginDao).findByPlatform("ZHIHU");
    }

    /** 只构造输入校验用客户端，不依赖平台账号。 */
    private ZhihuBrowserClient client(PlatformBrowserLoginDao loginDao) {
        return new ZhihuBrowserClient(new PublicationBrowserProperties(), mock(PlaywrightBrowserComponent.class), loginDao);
    }
}
