package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.domain.service.login.PlatformBrowserLoginService;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 独立浏览器登录接口的轻量回归测试；确认页面请求不再依赖单独口令，不启动真实浏览器。
 */
class PlatformBrowserLoginControllerTest {

    /**
     * 平台列表可以直接读取，且响应禁止缓存。
     */
    @Test
    void 无需令牌即可查询平台列表() {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var platform = new PlatformBrowserLoginService.PlatformLoginView("ZHIHU", "知乎", true,
                PlatformBrowserLoginStatus.NOT_LOGGED_IN, null, null, false,
                List.of("qr", "sms", "password"), List.of(PlatformScanLoginMethod.PLATFORM_APP));
        when(service.listPlatforms()).thenReturn(List.of(platform));
        var response = new MockHttpServletResponse();

        var result = new PlatformBrowserLoginController(service).listPlatforms(response);

        assertEquals(List.of(platform), result);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /** 批量检测接口只调用已登录平台检测服务，且响应禁止缓存。 */
    @Test
    void 批量检测已登录平台() {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var platform = new PlatformBrowserLoginService.PlatformLoginView("ZHIHU", "知乎", true,
                PlatformBrowserLoginStatus.LOGGED_IN, "见微", null, true,
                List.of("qr", "sms", "password"), List.of(PlatformScanLoginMethod.PLATFORM_APP));
        when(service.checkLoggedInPlatforms()).thenReturn(List.of(platform));
        var response = new MockHttpServletResponse();

        var result = new PlatformBrowserLoginController(service).checkLoggedInPlatforms(response);

        assertEquals(List.of(platform), result);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(service).checkLoggedInPlatforms();
    }

    /**
     * 登录操作只路由到目标平台，扫码任务由服务层负责。
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 无需令牌即可启动平台登录(CapturedOutput output) {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var response = new MockHttpServletResponse();
        when(service.startLogin(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.WECHAT, false))
                .thenReturn(new PlatformBrowserLoginService.LoginProgress(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "private-qr-image-test", null));

        new PlatformBrowserLoginController(service).startLogin("ZHIHU", "WECHAT", false, response);

        verify(service).startLogin(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.WECHAT, false);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(output.toString().contains("打开平台登录页 response platform=ZHIHU status=LOGIN_PENDING"));
        assertFalse(output.toString().contains("private-qr-image-test"));
    }

    /** 密码仅转发给当前平台登录任务，不进入持久化接口。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 密码登录只调用当前浏览器任务(CapturedOutput output) {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var response = new MockHttpServletResponse();
        var request = new PlatformBrowserLoginController.PasswordLoginRequest(
                "user@example.com", "private-password-test", false);
        when(service.submitPasswordLogin(PublicationPlatformType.ZHIHU,
                "user@example.com", "private-password-test", false))
                .thenReturn(new PlatformBrowserLoginService.LoginProgress(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, null, null));

        new PlatformBrowserLoginController(service).submitPasswordLogin("ZHIHU", request, response);

        verify(service).submitPasswordLogin(PublicationPlatformType.ZHIHU,
                "user@example.com", "private-password-test", false);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(output.toString().contains("提交账号密码登录 response platform=ZHIHU status=LOGIN_PENDING"));
        assertFalse(output.toString().contains("private-password-test"));
        assertFalse(output.toString().contains("user@example.com"));
    }

    /** 空验证码在进入浏览器前被拒绝，避免触发无效的远端操作。 */
    @Test
    void 空短信码不进入浏览器任务() {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var response = new MockHttpServletResponse();
        var request = new PlatformBrowserLoginController.SmsCodeRequest(" ");

        assertThrows(PlatformBrowserLoginException.class,
                () -> new PlatformBrowserLoginController(service).submitSmsCode("ZHIHU", request, response));
        verifyNoInteractions(service);
    }

    /** 抖音身份验证方式只转给当前登录服务，响应和日志不包含页面截图。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 抖音扫码后选择短信身份验证(CapturedOutput output) {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var response = new MockHttpServletResponse();
        when(service.selectDouyinVerificationMethod(PublicationPlatformType.DOUYIN,
                DouyinIdentityVerificationMethod.RECEIVE_SMS))
                .thenReturn(new PlatformBrowserLoginService.LoginProgress(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, "private-verification-image", null));

        new PlatformBrowserLoginController(service).selectIdentityVerificationMethod(
                "DOUYIN", DouyinIdentityVerificationMethod.RECEIVE_SMS, response);

        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(service).selectDouyinVerificationMethod(PublicationPlatformType.DOUYIN,
                DouyinIdentityVerificationMethod.RECEIVE_SMS);
        assertFalse(output.toString().contains("private-verification-image"));
    }

    /** 用户输入的身份验证码不得进入日志，也不能绕过输入校验。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 抖音身份验证码不记录到日志(CapturedOutput output) {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var response = new MockHttpServletResponse();
        var request = new PlatformBrowserLoginController.SmsCodeRequest("987654");
        when(service.submitDouyinVerificationCode(PublicationPlatformType.DOUYIN, "987654"))
                .thenReturn(new PlatformBrowserLoginService.LoginProgress(
                        PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, null, null));

        new PlatformBrowserLoginController(service).submitIdentityVerificationCode("DOUYIN", request, response);

        verify(service).submitDouyinVerificationCode(PublicationPlatformType.DOUYIN, "987654");
        assertFalse(output.toString().contains("987654"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /** 登录失败时保留可供页面展示的原因，日志不展开浏览器内部会话。 */
    @Test
    void 登录异常返回可读原因() {
        PlatformBrowserLoginService service = mock(PlatformBrowserLoginService.class);
        var exception = new PlatformBrowserLoginException("小红书加载二维码失败，请检查网络后重试");

        var response = new PlatformBrowserLoginController(service).handleLoginError(exception);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(exception.getMessage(), response.getBody().get("message"));
    }
}
