package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 扫码平台账号响应的状态判定测试；不启动浏览器或访问真实账号。 */
class QrPlatformPlaywrightLoginClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QrPlatformPlaywrightLoginClient client = new QrPlatformPlaywrightLoginClient(
            objectMapper, new PlaywrightBrowserComponent());

    /** 只有含扫码页及账号核验规则的平台才开放独立登录入口。 */
    @Test
    void 仅已实现的扫码平台显示登录入口() {
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.JUEJIN));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.WEIBO));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.BILIBILI));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.TOUTIAO));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.XIAOHONGSHU));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.DOUYIN));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.DAYU));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.CSDN));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.SMZDM));
        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.EASTMONEY));
        assertFalse(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.YUQUE));
    }

    /** 东方财富只接受创作平台返回的作者 ID 与昵称，不把业务成功码单独当成登录。 */
    @Test
    void 东方财富作者响应须包含身份() throws JsonProcessingException {
        var visitor = objectMapper.readTree("{\"Success\":-1,\"Message\":\"未登录\",\"Result\":null}");
        var missing = objectMapper.readTree("{\"Success\":1,\"Result\":{\"nickName\":\"作者\"}}");
        var author = objectMapper.readTree(
                "{\"Success\":1,\"Result\":{\"relatedUid\":123,\"nickName\":\"作者\"}}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, client.parseEastmoneyAccount(visitor).status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, client.parseEastmoneyAccount(missing).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, client.parseEastmoneyAccount(author).status());
        assertEquals("作者", client.parseEastmoneyAccount(author).accountName());
    }

    /** CSDN 创作后台只有成功业务码和当前用户名同时具备时才保存扫码会话。 */
    @Test
    void CSDN账号响应须含用户名() throws JsonProcessingException {
        var valid = objectMapper.readTree("{\"code\":200,\"data\":{\"username\":\"author-1\",\"nickname\":\"作者\"}}");
        var missing = objectMapper.readTree("{\"code\":200,\"data\":{\"nickname\":\"作者\"}}");
        var denied = objectMapper.readTree("{\"code\":401,\"data\":{\"username\":\"author-1\"}}");

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.CSDN, valid).status());
        assertEquals("作者", client.parseAccountResponse(PublicationPlatformType.CSDN, valid).accountName());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.CSDN, missing).status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.CSDN, denied).status());
    }

    /** 什么值得买返回 HTTP 200 时仍须检查正数用户 ID。 */
    @Test
    void 什么值得买账号响应须含有效用户ID() throws JsonProcessingException {
        var visitor = objectMapper.readTree("{\"smzdm_id\":0}");
        var account = objectMapper.readTree("{\"smzdm_id\":123,\"nickname\":\"值友\"}");
        var missing = objectMapper.readTree("{\"nickname\":\"值友\"}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.SMZDM, visitor).status());
        assertEquals("值友", client.parseAccountResponse(PublicationPlatformType.SMZDM, account).accountName());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.SMZDM, missing).status());
    }

    /** 掘金未登录会返回 HTTP 200 和空 data，不能把请求成功误当登录成功。 */
    @Test
    void 掘金空账号数据应判定未登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"err_no\":0,\"data\":null}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.JUEJIN, response).status());
    }

    /** 掘金账号 ID 与名称可用时才保存会话。 */
    @Test
    void 掘金有效账号应判定已登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"err_no\":0,\"data\":{\"user_id\":\"123\",\"user_name\":\"掘金账号\"}}");

        var probe = client.parseAccountResponse(PublicationPlatformType.JUEJIN, response);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("掘金账号", probe.accountName());
    }

    /** 掘金账号响应格式变化时不能伪造已登录结论。 */
    @Test
    void 掘金缺少账号标识应判定无法确认() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"err_no\":0,\"data\":{\"user_name\":\"掘金账号\"}}");

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.JUEJIN, response).status());
    }

    /** 哔哩哔哩未登录业务码不能保存半成品会话。 */
    @Test
    void 哔哩哔哩未登录业务码应返回未登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"code\":-101,\"message\":\"账号未登录\"}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.BILIBILI, response).status());
    }

    /** 哔哩哔哩账号接口须同时确认登录标志和用户 ID。 */
    @Test
    void 哔哩哔哩真实账号信息应返回已登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":123,\"uname\":\"视频账号\"}}");

        var probe = client.parseAccountResponse(PublicationPlatformType.BILIBILI, response);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("视频账号", probe.accountName());
    }

    /** 哔哩哔哩缺少用户 ID 时保留未知，不把业务成功码当作登录成功。 */
    @Test
    void 哔哩哔哩缺少用户标识应返回无法确认() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"code\":0,\"data\":{\"isLogin\":true}}");

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.BILIBILI, response).status());
    }

    /** 头条号明确返回未登录业务码时不保存会话。 */
    @Test
    void 头条号未登录业务码应返回未登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"err_no\":100004,\"message\":\"user not login\"}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.TOUTIAO, response).status());
    }

    /** 头条号用户 ID 与昵称均来自平台账号接口，不从二维码图片推测账号。 */
    @Test
    void 头条号真实账号信息应返回已登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"err_no\":0,\"data\":{\"user\":{\"id\":123,\"screen_name\":\"头条账号\"}}}");

        var probe = client.parseAccountResponse(PublicationPlatformType.TOUTIAO, response);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("头条账号", probe.accountName());
    }

    /** 微信公众平台登录页只有空占位值和二维码，不能判成已登录。 */
    @Test
    void 微信公众号扫码页应返回未登录() {
        String html = "<img class='login__type__container__scan__qrcode'>"
                + "<script>data: { t: \"\", nick_name: \"\" }</script>";

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseWechatAccountHtml(html).status());
    }

    /** 微信公众平台必须同时给出后台 token 和账号昵称才保存会话。 */
    @Test
    void 微信公众号后台账号信息应返回已登录() {
        String html = "<script>data: { t: \"123456\", nick_name: \"公众号账号\" }</script>";

        var probe = client.parseWechatAccountHtml(html);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("公众号账号", probe.accountName());
    }

    /** 小程序后台页面不能作为公众号文章发布账号保存。 */
    @Test
    void 小程序页面不能认作公众号账号() {
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseWechatAccountHtml("<h1>小程序开发与发布流程</h1>").status());
    }

    /** 微博文章编辑器未登录时只返回跳转页，不能按 HTTP 200 认定登录。 */
    @Test
    void 微博未登录跳转页应返回未登录() throws Exception {
        String html = "<meta http-equiv=\"refresh\" content=\"0; url=https://weibo.com/\">";

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseWeiboAccountHtml(html).status());
    }

    /** 微博文章编辑器的账号配置须包含 uid，昵称只是展示字段。 */
    @Test
    void 微博有效账号配置应返回已登录() throws Exception {
        String html = "<script>var page = {config: JSON.parse('{\"uid\":\"123\",\"nick\":\"微博账号\"}')};</script>";

        var probe = client.parseWeiboAccountHtml(html);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("微博账号", probe.accountName());
    }

    /** 小红书的未登录业务码不能因为接口 HTTP 成功就保存会话。 */
    @Test
    void 小红书未登录业务码应返回未登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"success\":false,\"result\":-100}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.XIAOHONGSHU, response).status());
    }

    /** 小红书创作者账号接口提供真实用户标识时才认定登录。 */
    @Test
    void 小红书有效账号应返回已登录() throws JsonProcessingException {
        var response = objectMapper.readTree(
                "{\"success\":true,\"data\":{\"userId\":\"123\",\"userName\":\"创作者\"}}");

        var probe = client.parseAccountResponse(PublicationPlatformType.XIAOHONGSHU, response);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("创作者", probe.accountName());
    }

    /** 抖音创作者接口明确返回未登录业务码时不得保存二维码会话。 */
    @Test
    void 抖音未登录业务码应返回未登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"status_code\":8,\"status_msg\":\"用户未登录\"}");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN,
                client.parseAccountResponse(PublicationPlatformType.DOUYIN, response).status());
    }

    /** 抖音成功码之外还需要当前用户标识，才可确认账号。 */
    @Test
    void 抖音缺用户标识应返回无法确认() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"status_code\":0,\"data\":{}}");

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseAccountResponse(PublicationPlatformType.DOUYIN, response).status());
    }

    /** 抖音创作者接口返回用户标识后才允许保存扫码会话。 */
    @Test
    void 抖音创作者账号可确认登录() throws JsonProcessingException {
        var response = objectMapper.readTree("{\"status_code\":0,\"user_info\":{\"uid\":\"123\",\"nickname\":\"作者\"}}");

        var probe = client.parseAccountResponse(PublicationPlatformType.DOUYIN, response);

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("作者", probe.accountName());
    }

    /** 抖音创作者主页显示本账号抖音号时，可以不依赖旧账号接口直接确认登录。 */
    @Test
    void 抖音主页账号标识可以确认登录() {
        var probe = client.parseDouyinPageAccount("https://creator.douyin.com/creator-micro/home",
                "后厂卷王 | 抖音号：28398064639 | 关注 158");

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("28398064639", probe.accountName());
    }

    /** 没有账号标识或不是抖音创作者主页时，不得把页面跳转判定为登录。 */
    @Test
    void 抖音登录页或无账号主页不能确认登录() {
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseDouyinPageAccount("https://creator.douyin.com/creator-micro/home",
                        "请扫码登录抖音账号").status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                client.parseDouyinPageAccount("https://creator.douyin.com/login",
                        "抖音号：28398064639").status());
    }

    /** 大鱼号后台重定向到登录页时不能按 HTTP 200 判定已登录。 */
    @Test
    void 大鱼号跳回登录页应返回未登录() {
        var probe = client.parseDayuAccountHtml("https://mp.dayu.com/?redirect_url=/dashboard/index", "登录");

        assertEquals(PlatformBrowserLoginStatus.NOT_LOGGED_IN, probe.status());
    }

    /** 大鱼号后台配置必须同时包含令牌和账号 ID。 */
    @Test
    void 大鱼号后台账号配置可以确认身份() {
        var probe = client.parseDayuAccountHtml("https://mp.dayu.com/dashboard/index",
                "var globalConfig = {utoken:'session',wmid:123,weMediaName:'大鱼作者'};");

        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, probe.status());
        assertEquals("大鱼作者", probe.accountName());
    }

}
