package org.huangry.colorful.geo.domain.service.login;

import org.huangry.colorful.geo.infrastructure.client.login.FormPlatformPlaywrightLoginClient;
import org.huangry.colorful.geo.infrastructure.client.login.PlatformBrowserLoginRouter;
import org.huangry.colorful.geo.infrastructure.client.login.PlatformThirdPartyLoginClient;
import org.huangry.colorful.geo.infrastructure.client.login.QrPlatformPlaywrightLoginClient;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformLoginInteractionStage;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 核对知乎与其他平台共享登录路由，浏览器和数据库均使用替身。 */
class PlatformBrowserLoginServiceTest {

    private PlatformBrowserLoginDao dao;
    private QrPlatformPlaywrightLoginClient qrClient;
    private FormPlatformPlaywrightLoginClient formClient;
    private PlatformThirdPartyLoginClient thirdPartyClient;
    private PlatformBrowserLoginService service;

    @BeforeEach
    void setUp() {
        dao = mock(PlatformBrowserLoginDao.class);
        qrClient = mock(QrPlatformPlaywrightLoginClient.class);
        formClient = mock(FormPlatformPlaywrightLoginClient.class);
        thirdPartyClient = mock(PlatformThirdPartyLoginClient.class);
        when(qrClient.handlesScanMethod(any(), any())).thenAnswer(invocation -> {
            PublicationPlatformType platform = invocation.getArgument(0);
            PlatformScanLoginMethod method = invocation.getArgument(1);
            return method == PlatformScanLoginMethod.PLATFORM_APP
                    || method == PlatformScanLoginMethod.WECHAT
                    && (platform == PublicationPlatformType.CSDN || platform == PublicationPlatformType.SMZDM);
        });
        service = new PlatformBrowserLoginService(dao,
                new PlatformBrowserLoginRouter(qrClient, formClient, thirdPartyClient));
    }

    /** 知乎 App 二维码由通用扫码客户端处理，不启动第三方授权。 */
    @Test
    void zhihuAppUsesQrClient() {
        when(qrClient.startLogin(PublicationPlatformType.ZHIHU))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "qr", null));

        var result = service.startLogin(PublicationPlatformType.ZHIHU,
                PlatformScanLoginMethod.PLATFORM_APP, false);

        assertEquals("qr", result.screenshotBase64());
        verify(formClient).cancelLogin(PublicationPlatformType.ZHIHU);
        verify(thirdPartyClient, never()).startLogin(any(), any(), eq(false));
    }

    /** 知乎微信和 QQ 扫码由通用第三方授权客户端处理。 */
    @Test
    void zhihuSocialLoginUsesThirdPartyClient() {
        for (PlatformScanLoginMethod method : new PlatformScanLoginMethod[] {
                PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ }) {
            when(thirdPartyClient.startLogin(PublicationPlatformType.ZHIHU, method, false))
                    .thenReturn(new PlatformThirdPartyLoginClient.BrowserLoginSnapshot(
                            PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "social", null));

            assertEquals("social", service.startLogin(PublicationPlatformType.ZHIHU, method, false)
                    .screenshotBase64());
            verify(thirdPartyClient).startLogin(PublicationPlatformType.ZHIHU, method, false);
        }
        verify(qrClient, never()).startLogin(PublicationPlatformType.ZHIHU);
    }

    /** 短信与密码提交都使用通用表单客户端，提交时不提前落库。 */
    @Test
    void zhihuFormSubmissionDoesNotSaveSession() {
        when(formClient.requestSmsCode(PublicationPlatformType.ZHIHU, "13800138000", false))
                .thenReturn(new FormPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, null));
        when(formClient.submitPasswordLogin(PublicationPlatformType.ZHIHU,
                "user@example.com", "password", false))
                .thenReturn(new FormPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, null));

        service.requestSmsCode(PublicationPlatformType.ZHIHU, "13800138000", false);
        service.submitPasswordLogin(PublicationPlatformType.ZHIHU,
                "user@example.com", "password", false);

        verify(dao, never()).saveSuccessfulLogin(any(), any(), any());
    }

    /** 当前是知乎扫码任务时，账号核验成功才保存知乎会话。 */
    @Test
    void activeZhihuQrLoginSavesConfirmedSession() {
        when(qrClient.isActiveFor(PublicationPlatformType.ZHIHU)).thenReturn(true);
        when(qrClient.readLoginProgress(PublicationPlatformType.ZHIHU))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGGED_IN, "见微", null, "state"));

        assertEquals(PlatformBrowserLoginTaskStatus.LOGGED_IN,
                service.readLoginProgress(PublicationPlatformType.ZHIHU).status());
        verify(dao).saveSuccessfulLogin("ZHIHU", "见微", "state");
    }

    /** 取消一次登录只结束当前任务，不覆盖此前已保存的账号登录结论。 */
    @Test
    void cancelledLoginTaskDoesNotChangeSavedAccountStatus() {
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setLoginStatus(PlatformBrowserLoginStatus.LOGGED_IN.name());
        saved.setStorageState("{\"cookies\":[]}");
        when(dao.findByPlatform("ZHIHU")).thenReturn(saved);
        when(qrClient.isActiveFor(PublicationPlatformType.ZHIHU)).thenReturn(true);
        when(qrClient.readLoginProgress(PublicationPlatformType.ZHIHU))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.NOT_STARTED, null, null, null));

        assertEquals(PlatformBrowserLoginTaskStatus.NOT_STARTED,
                service.readLoginProgress(PublicationPlatformType.ZHIHU).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, service.listPlatforms().stream()
                .filter(platform -> platform.platformType().equals("ZHIHU")).findFirst().orElseThrow().status());
        verify(dao, never()).saveSuccessfulLogin(any(), any(), any());
    }

    /** 知乎扫码、表单与第三方入口都通过能力列表展示。 */
    @Test
    void zhihuListsGenericLoginMethods() {
        var zhihu = service.listPlatforms().stream()
                .filter(platform -> platform.platformType().equals("ZHIHU")).findFirst().orElseThrow();

        assertTrue(zhihu.supported());
        assertEquals(java.util.List.of("qr", "sms", "password"), zhihu.loginMethods());
        assertEquals(java.util.List.of(PlatformScanLoginMethod.PLATFORM_APP,
                PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ), zhihu.scanMethods());
    }

    /** 语雀当前仅开放已核对的微信授权，不误展示尚未接通的表单入口。 */
    @Test
    void yuqueOnlyListsConnectedWechatMethod() {
        var yuque = service.listPlatforms().stream()
                .filter(platform -> platform.platformType().equals("YUQUE")).findFirst().orElseThrow();

        assertTrue(yuque.supported());
        assertEquals(java.util.List.of("qr"), yuque.loginMethods());
        assertEquals(java.util.List.of(PlatformScanLoginMethod.WECHAT), yuque.scanMethods());
    }

    /** 东方财富原生 App 码与微信、QQ 授权都通过扫码入口展示。 */
    @Test
    void eastmoneyListsConfirmedScanMethods() {
        var eastmoney = service.listPlatforms().stream()
                .filter(platform -> platform.platformType().equals("EASTMONEY")).findFirst().orElseThrow();

        assertTrue(eastmoney.supported());
        assertEquals(java.util.List.of("qr"), eastmoney.loginMethods());
        assertEquals(java.util.List.of(PlatformScanLoginMethod.PLATFORM_APP,
                PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ), eastmoney.scanMethods());
    }

    /** 一点号只展示实际打开过授权二维码的微信和 QQ。 */
    @Test
    void yidianListsConfirmedScanMethods() {
        var yidian = service.listPlatforms().stream()
                .filter(platform -> platform.platformType().equals("YIDIAN")).findFirst().orElseThrow();

        assertTrue(yidian.supported());
        assertEquals(java.util.List.of("qr"), yidian.loginMethods());
        assertEquals(java.util.List.of(PlatformScanLoginMethod.WECHAT,
                PlatformScanLoginMethod.QQ), yidian.scanMethods());
    }

    /** 其他扫码平台仍由同一个二维码客户端处理，不依赖知乎规则。 */
    @Test
    void juejinQrLoginKeepsItsOwnAccount() {
        when(qrClient.isActiveFor(PublicationPlatformType.JUEJIN)).thenReturn(true);
        when(qrClient.readLoginProgress(PublicationPlatformType.JUEJIN))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGGED_IN, "掘金作者", null, "juejin-state"));

        assertEquals(PlatformBrowserLoginTaskStatus.LOGGED_IN,
                service.readLoginProgress(PublicationPlatformType.JUEJIN).status());
        verify(dao).saveSuccessfulLogin("JUEJIN", "掘金作者", "juejin-state");
    }

    /** 原生扫码未拿到账号时，不写入半成品会话。 */
    @Test
    void pendingQrLoginDoesNotSaveSession() {
        when(qrClient.isActiveFor(PublicationPlatformType.ZHIHU)).thenReturn(true);
        when(qrClient.readLoginProgress(PublicationPlatformType.ZHIHU))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "image", null));

        service.readLoginProgress(PublicationPlatformType.ZHIHU);

        verify(dao, never()).saveSuccessfulLogin(any(), any(), any());
    }

    /** 抖音二次验证仍处于登录中，只显示阶段，不保存未经账号接口确认的会话。 */
    @Test
    void douyinVerificationRemainsPendingUntilAccountIsConfirmed() {
        when(qrClient.isActiveFor(PublicationPlatformType.DOUYIN)).thenReturn(true);
        when(qrClient.readLoginProgress(PublicationPlatformType.DOUYIN))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "verification-page", null,
                        PlatformLoginInteractionStage.DOUYIN_IDENTITY_VERIFICATION));
        when(qrClient.selectDouyinVerificationMethod(DouyinIdentityVerificationMethod.RECEIVE_SMS))
                .thenReturn(new QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "sms-page", null,
                        PlatformLoginInteractionStage.DOUYIN_SMS_CODE));

        assertEquals(PlatformLoginInteractionStage.DOUYIN_IDENTITY_VERIFICATION,
                service.readLoginProgress(PublicationPlatformType.DOUYIN).interactionStage());
        assertEquals(PlatformLoginInteractionStage.DOUYIN_SMS_CODE,
                service.selectDouyinVerificationMethod(PublicationPlatformType.DOUYIN,
                        DouyinIdentityVerificationMethod.RECEIVE_SMS).interactionStage());
        verify(dao, never()).saveSuccessfulLogin(any(), any(), any());
    }

    /** 身份验证接口不得跨平台操作当前抖音浏览器任务。 */
    @Test
    void douyinVerificationRejectsOtherPlatforms() {
        assertThrows(PlatformBrowserLoginException.class, () -> service.selectDouyinVerificationMethod(
                PublicationPlatformType.ZHIHU, DouyinIdentityVerificationMethod.RECEIVE_SMS));
        verify(qrClient, never()).selectDouyinVerificationMethod(any());
    }

    /** 第三方授权只有目标平台账号核验成功后才能落库。 */
    @Test
    void thirdPartyResultSavesTargetPlatformOnly() {
        when(thirdPartyClient.isActiveFor(PublicationPlatformType.JIANSHU)).thenReturn(true);
        when(thirdPartyClient.readLoginProgress(PublicationPlatformType.JIANSHU))
                .thenReturn(new PlatformThirdPartyLoginClient.BrowserLoginSnapshot(
                        PlatformBrowserLoginTaskStatus.LOGGED_IN, "简书作者", null, "jianshu-state"));

        service.readLoginProgress(PublicationPlatformType.JIANSHU);

        verify(dao).saveSuccessfulLogin("JIANSHU", "简书作者", "jianshu-state");
    }

    /** 数据库中的知乎会话由通用表单账号核验器检测。 */
    @Test
    void savedZhihuSessionUsesFormAccountProbe() {
        PlatformBrowserLoginEntity saved = new PlatformBrowserLoginEntity();
        saved.setStorageState("{\"cookies\":[]}");
        when(dao.findByPlatform("ZHIHU")).thenReturn(saved);
        when(formClient.verifySavedLogin(PublicationPlatformType.ZHIHU, saved.getStorageState()))
                .thenReturn(new FormPlatformPlaywrightLoginClient.AccountProbe(
                        PlatformBrowserLoginStatus.UNKNOWN, null));

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                service.checkLogin(PublicationPlatformType.ZHIHU).status());
        verify(dao).updateCheckedStatus("ZHIHU", "UNKNOWN", null);
    }

    /** 未接入平台不能启动任意浏览器登录任务。 */
    @Test
    void unsupportedPlatformIsRejected() {
        assertThrows(PlatformBrowserLoginException.class, () -> service.startLogin(
                PublicationPlatformType.DOUBAN, PlatformScanLoginMethod.PLATFORM_APP, false));
    }
}
