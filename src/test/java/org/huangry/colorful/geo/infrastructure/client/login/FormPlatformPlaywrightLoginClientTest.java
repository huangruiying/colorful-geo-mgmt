package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表单登录的平台能力和账号响应测试；不访问第三方网站，也不提交真实凭据。
 */
class FormPlatformPlaywrightLoginClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FormPlatformPlaywrightLoginClient client = new FormPlatformPlaywrightLoginClient(
            objectMapper, new PlaywrightBrowserComponent());

    /** 简书只开放已核对的密码入口。 */
    @Test
    void 简书只有密码登录() {
        assertTrue(FormPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.JIANSHU));
        assertFalse(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.JIANSHU));
    }

    /** 语雀第三方授权只在服务端页面明确给出用户 ID 后才可保存会话。 */
    @Test
    void 语雀账号核验不把匿名默认头像判为登录() {
        assertTrue(FormPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.YUQUE));
        assertFalse(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.YUQUE));
        String anonymous = yuquePage("{\"me\":{\"avatar_url\":\"default.png\"}}");
        String loggedIn = yuquePage("{\"me\":{\"id\":42,\"name\":\"作者\"}}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, client.parseYuqueAccount(anonymous).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, client.parseYuqueAccount(loggedIn).status());
        assertEquals("作者", client.parseYuqueAccount(loggedIn).accountName());
    }

    /** 一点号无媒体标识时不把普通统计响应判为已登录。 */
    @Test
    void 一点号账号核验要求媒体标识和名称() throws Exception {
        assertTrue(FormPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.YIDIAN));
        assertFalse(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.YIDIAN));
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, client.parseYidianAccount(
                objectMapper.readTree("{\"code\":0,\"result\":{}}")).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, client.parseYidianAccount(
                objectMapper.readTree("{\"code\":0,\"result\":{\"media\":{\"id\":123,\"media_name\":\"作者\"}}}"))
                .status());
    }

    private String yuquePage(String appData) {
        return "<script>window.appData = JSON.parse(decodeURIComponent(\""
                + URLEncoder.encode(appData, StandardCharsets.UTF_8) + "\"))</script>";
    }

    /** 已核对的平台页面存在短信入口。 */
    @Test
    void 博客园和百家号支持短信登录() {
        assertTrue(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.CNBLOGS));
        assertTrue(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.BAIJIAHAO));
        assertTrue(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.OSCHINA));
        assertTrue(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.SEGMENTFAULT));
        assertFalse(FormPlatformPlaywrightLoginClient.supportsPasswordLogin(PublicationPlatformType.SEGMENTFAULT));
    }

    /** 简书仅在响应中同时有账号标识和昵称时认定登录。 */
    @Test
    void 简书缺账号标识不保存会话() throws Exception {
        var probe = client.parseJianshuAccount(objectMapper.readTree("{\"data\":{}}"));

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, probe.status());
    }

    /** 博客园登录链接不能把未登录页面误判为成功。 */
    @Test
    void 博客园未登录页面明确返回未登录() {
        var probe = client.parseCnblogsAccount("<div id=\"header_user_right\">"
                + "<a onclick=\"return login();\">登录</a></div>");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 百家号业务错误码代表登录已退出，即使 HTTP 是 200。 */
    @Test
    void 百家号退出错误码不能当作登录成功() throws Exception {
        var probe = client.parseBaijiahaoAccount(objectMapper.readTree("{\"errno\":10001401,\"data\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 百家号仅在业务成功且取得用户 ID 后认定登录。 */
    @Test
    void 百家号明确账号可认定登录() throws Exception {
        var probe = client.parseBaijiahaoAccount(objectMapper.readTree(
                "{\"errno\":0,\"errmsg\":\"success\",\"data\":{\"user\":{\"userid\":\"42\",\"name\":\"作者\"}}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("作者", probe.accountName());
    }

    /** 开源中国的未登录业务码不能被 HTTP 200 掩盖。 */
    @Test
    void 开源中国未登录业务码返回未登录() throws Exception {
        var probe = client.parseOschinaAccount(objectMapper.readTree(
                "{\"success\":false,\"code\":40001,\"result\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 开源中国需要明确 userId 才可保存会话。 */
    @Test
    void 开源中国有用户标识才确认登录() throws Exception {
        var probe = client.parseOschinaAccount(objectMapper.readTree(
                "{\"success\":true,\"result\":{\"userId\":123,\"userVo\":{\"name\":\"作者\"}}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("作者", probe.accountName());
    }

    /** 思否登录页没有当前用户链接，不保存访客会话。 */
    @Test
    void 思否访客页面返回未登录() {
        var probe = client.parseSegmentfaultAccount("<h3>注册登录</h3>");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 思否个人设置页上的账号链接作为身份依据。 */
    @Test
    void 思否个人设置页取得账号() {
        var probe = client.parseSegmentfaultAccount("<a href=\"/u/writer\">作者</a>");

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("writer", probe.accountName());
    }

    /** 慕课网未登录 JSONP 返回明确的访客业务码。 */
    @Test
    void 慕课网未登录响应不能保存会话() {
        var probe = client.parseImoocAccount("jsonpcallback({\"result\":-11,\"data\":\"\"})");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 慕课网取得 uid 和昵称后才确认身份。 */
    @Test
    void 慕课网账号响应确认登录() {
        var probe = client.parseImoocAccount("jsonpcallback({\"result\":0,"
                + "\"data\":{\"uid\":42,\"nickname\":\"作者\"}})\n");

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("作者", probe.accountName());
    }

    /** 网易号未登录业务码不能当成已登录。 */
    @Test
    void 网易号未登录响应明确返回未登录() throws Exception {
        var probe = client.parseNeteaseAccount(objectMapper.readTree("{\"code\":100021,\"data\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 网易号账号响应包含 tid 后方能保存会话。 */
    @Test
    void 网易号返回账号标识才确认登录() throws Exception {
        var probe = client.parseNeteaseAccount(objectMapper.readTree(
                "{\"code\":1,\"data\":{\"tid\":\"100\",\"tname\":\"网易作者\"}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("网易作者", probe.accountName());
    }

    /** 网易号返回成功码但缺少账号标识时不能保存会话。 */
    @Test
    void 网易号缺账号标识不确认登录() throws Exception {
        var probe = client.parseNeteaseAccount(objectMapper.readTree("{\"code\":1,\"data\":{}}"));

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, probe.status());
    }

    /** 搜狐号未登录业务码不会被 HTTP 200 掩盖。 */
    @Test
    void 搜狐号登录错误码返回未登录() throws Exception {
        var probe = client.parseSohuAccount(objectMapper.readTree("{\"code\":1211,\"success\":false}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 搜狐号须同时有成功码和可用的创作账号 ID。 */
    @Test
    void 搜狐号账号列表确认登录() throws Exception {
        var probe = client.parseSohuAccount(objectMapper.readTree("{\"code\":2000000,"
                + "\"data\":{\"data\":[{\"accounts\":[{\"id\":42,\"nickName\":\"搜狐作者\"}]}]}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("搜狐作者", probe.accountName());
    }

}
