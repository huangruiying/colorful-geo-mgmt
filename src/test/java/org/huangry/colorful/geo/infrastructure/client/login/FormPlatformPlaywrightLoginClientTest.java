package org.huangry.colorful.geo.infrastructure.client.login;

import com.alibaba.fastjson.JSON;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表单登录的平台能力和账号响应测试；不访问第三方网站，也不提交真实凭据。
 */
class FormPlatformPlaywrightLoginClientTest {

    private final PlatformAccountLoginClient client = new PlatformAccountLoginClient();

    /** 简书只开放已核对的密码入口。 */
    @Test
    void 简书只有密码登录() {
        assertTrue(PlatformAccountLoginClient.supportsPlatform(PublicationPlatformType.JIANSHU));
        assertFalse(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.JIANSHU));
    }

    /** 一点号以协议接口签署人账号 ID 判定登录，统计响应或异常业务码不算登录。 */
    @Test
    void 一点号按协议接口签署人账号ID判定登录() throws Exception {
        assertTrue(PlatformAccountLoginClient.supportsPlatform(PublicationPlatformType.YIDIAN));
        assertFalse(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.YIDIAN));
        // 真实响应结构：已登录 userId 为数字，未登录为空字符串；姓名、手机号已脱敏
        var loggedIn = client.parseYidianAccount(JSON.parseObject(
                "{\"status\":\"success\",\"code\":0,\"result\":{\"type\":1,\"userName\":\"张三\","
                        + "\"userMobile\":\"138****0000\",\"userId\":7673532}}"));
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, loggedIn.status());
        assertEquals("张三", loggedIn.accountName());
        assertEquals("7673532", client.parseYidianAccount(JSON.parseObject(
                "{\"code\":0,\"result\":{\"userName\":\"\",\"userId\":7673532}}")).accountName());
        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, client.parseYidianAccount(JSON.parseObject(
                "{\"status\":\"success\",\"code\":0,\"result\":{\"userName\":\"\",\"userMobile\":\"\",\"userId\":\"\"}}"))
                .status());
        // 统计接口那种不含签署人字段的响应，或业务码异常，都不能判为登录
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, client.parseYidianAccount(
                JSON.parseObject("{\"code\":0,\"result\":{\"viewSum\":0}}")).status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, client.parseYidianAccount(
                JSON.parseObject("{\"code\":1,\"result\":{\"userId\":7673532}}")).status());
    }

    /** 已核对的平台页面存在短信入口。 */
    @Test
    void 博客园和百家号支持短信登录() {
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.CNBLOGS));
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.BAIJIAHAO));
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.OSCHINA));
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.SEGMENTFAULT));
        assertFalse(PlatformAccountLoginClient.supportsPasswordLogin(PublicationPlatformType.SEGMENTFAULT));
    }

    /** 简书仅在响应中同时有账号标识和昵称时认定登录。 */
    @Test
    void 简书缺账号标识不保存会话() throws Exception {
        var probe = client.parseJianshuAccount(JSON.parseObject("{\"data\":{}}"));

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
        var probe = client.parseBaijiahaoAccount(JSON.parseObject("{\"errno\":10001401,\"data\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 百家号仅在业务成功且取得用户 ID 后认定登录。 */
    @Test
    void 百家号明确账号可认定登录() throws Exception {
        var probe = client.parseBaijiahaoAccount(JSON.parseObject(
                "{\"errno\":0,\"errmsg\":\"success\",\"data\":{\"user\":{\"userid\":\"42\",\"name\":\"作者\"}}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("作者", probe.accountName());
    }

    /** 开源中国的未登录业务码不能被 HTTP 200 掩盖。 */
    @Test
    void 开源中国未登录业务码返回未登录() throws Exception {
        var probe = client.parseOschinaAccount(JSON.parseObject(
                "{\"success\":false,\"code\":40001,\"result\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 开源中国需要明确 userId 才可保存会话。 */
    @Test
    void 开源中国有用户标识才确认登录() throws Exception {
        var probe = client.parseOschinaAccount(JSON.parseObject(
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
        var probe = client.parseNeteaseAccount(JSON.parseObject("{\"code\":100021,\"data\":null}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 网易号账号响应包含 tid 后方能保存会话。 */
    @Test
    void 网易号返回账号标识才确认登录() throws Exception {
        var probe = client.parseNeteaseAccount(JSON.parseObject(
                "{\"code\":1,\"data\":{\"tid\":\"100\",\"tname\":\"网易作者\"}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("网易作者", probe.accountName());
    }

    /** 网易号返回成功码但缺少账号标识时不能保存会话。 */
    @Test
    void 网易号缺账号标识不确认登录() throws Exception {
        var probe = client.parseNeteaseAccount(JSON.parseObject("{\"code\":1,\"data\":{}}"));

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, probe.status());
    }

    /** 搜狐号未登录业务码不会被 HTTP 200 掩盖。 */
    @Test
    void 搜狐号登录错误码返回未登录() throws Exception {
        var probe = client.parseSohuAccount(JSON.parseObject("{\"code\":1211,\"success\":false}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 搜狐号须同时有成功码和可用的创作账号 ID。 */
    @Test
    void 搜狐号账号列表确认登录() throws Exception {
        var probe = client.parseSohuAccount(JSON.parseObject("{\"code\":2000000,"
                + "\"data\":{\"data\":[{\"accounts\":[{\"id\":42,\"nickName\":\"搜狐作者\"}]}]}}"));

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("搜狐作者", probe.accountName());
    }

}
