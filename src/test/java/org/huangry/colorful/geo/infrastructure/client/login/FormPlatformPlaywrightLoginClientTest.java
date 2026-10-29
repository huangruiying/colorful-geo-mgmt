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

    /** 搜狐焦点按 errorCode 判定登录，昵称为转义文本时还原成中文，缺昵称时取用户名。 */
    @Test
    void 搜狐焦点按业务码判定登录并取昵称() throws Exception {
        var notLoggedIn = client.parseSohuFocusAccount(JSON.parseObject(
                "{\"code\":401,\"msg\":\"用户未登录\",\"errorCode\":401}"));
        var escapedName = client.parseSohuFocusAccount(JSON.parseObject(
                "{\"errorCode\":0,\"data\":{\"nickName\":\"\\\\u7126\\\\u70b9\"}}"));
        var userNameOnly = client.parseSohuFocusAccount(JSON.parseObject(
                "{\"errorCode\":0,\"data\":{\"nickName\":\"\",\"userName\":\"focus_user\"}}"));
        var noName = client.parseSohuFocusAccount(JSON.parseObject("{\"errorCode\":0,\"data\":{}}"));
        var otherError = client.parseSohuFocusAccount(JSON.parseObject("{\"errorCode\":500}"));

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, notLoggedIn.status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, escapedName.status());
        assertEquals("焦点", escapedName.accountName());
        assertEquals("focus_user", userNameOnly.accountName());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, noName.status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, otherError.status());
    }

    /** 搜狐焦点只登记账号核验，不开放短信和密码登录。 */
    @Test
    void 搜狐焦点不开放短信和密码登录() {
        assertTrue(PlatformAccountLoginClient.supportsPlatform(PublicationPlatformType.SOHU_FOCUS));
        assertFalse(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.SOHU_FOCUS));
        assertFalse(PlatformAccountLoginClient.supportsPasswordLogin(PublicationPlatformType.SOHU_FOCUS));
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

    /** 网易号只开放邮箱密码登录，不展示手机号验证码入口。 */
    @Test
    void 网易号只支持邮箱密码登录() {
        assertTrue(PlatformAccountLoginClient.supportsPlatform(PublicationPlatformType.NETEASE));
        assertTrue(PlatformAccountLoginClient.supportsPasswordLogin(PublicationPlatformType.NETEASE));
        assertFalse(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.NETEASE));
    }

    /** 已核对的平台页面存在短信入口。 */
    @Test
    void 百家号支持短信登录() {
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.BAIJIAHAO));
    }

    /** 简书仅在响应中同时有账号标识和昵称时认定登录。 */
    @Test
    void 简书缺账号标识不保存会话() throws Exception {
        var probe = client.parseJianshuAccount(JSON.parseObject("{\"data\":{}}"));

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, probe.status());
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
