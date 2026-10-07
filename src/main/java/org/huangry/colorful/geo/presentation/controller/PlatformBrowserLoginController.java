package org.huangry.colorful.geo.presentation.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.login.PlatformBrowserLoginService;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 独立 Playwright 登录接口；提供已接入平台的扫码与状态检测，不负责管理后台鉴权。
 * 不改变现有 Wechatsync 登录状态接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/browser-login/platforms")
public class PlatformBrowserLoginController {

    private final PlatformBrowserLoginService loginService;

    /**
     * 查询各平台登录状态；只返回已保存的状态，不主动访问平台网站。
     *
     * @param response 用于禁止缓存敏感响应
     * @return 各平台登录状态
     */
    @GetMapping("/login-statuses")
    public List<PlatformBrowserLoginService.PlatformLoginView> listPlatforms(HttpServletResponse response) {
        log.info("查询浏览器登录平台 登录状态列表 request");
        disableCaching(response);
        List<PlatformBrowserLoginService.PlatformLoginView> platforms = loginService.listPlatforms();
        log.info("查询浏览器登录平台 登录状态列表 response platformCount={} supportedCount={}", platforms.size(),
                platforms.stream().filter(PlatformBrowserLoginService.PlatformLoginView::supported).count());
        return platforms;
    }

    /**
     * 打开目标平台登录页面；重复点击会关闭该平台上一次登录页面并重新打开。
     *
     * @param platform 平台枚举名
     * @param scanMethod 平台 App、微信或 QQ 扫码；历史 ZHIHU_APP 仍映射到平台 App
     * @param agreedToPlatformTerms 用户是否已同意目标平台登录协议
     * @param response 用于禁止缓存敏感响应
     * @return 登录页截图
     */
    @PostMapping("/{platform}/login")
    public PlatformBrowserLoginService.LoginProgress startLogin(
            @PathVariable("platform") String platform,
            @RequestParam(value = "scanMethod", required = false) String scanMethod,
            @RequestParam(value = "agreedToPlatformTerms", defaultValue = "false") boolean agreedToPlatformTerms,
            HttpServletResponse response) {
        log.info("打开平台登录页 request platform={} scanMethod={}", platform, scanMethod);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress loginProgress = loginService.startLogin(
                parsePlatform(platform), scanMethod == null ? null : parseScanMethod(scanMethod),
                agreedToPlatformTerms);
        log.info("打开平台登录页 response platform={} status={} interactionStage={}",
                platform, loginProgress.status(), loginProgress.interactionStage());
        return loginProgress;
    }

    /**
     * 手机号登录：点击发送手机验证码。
     *
     * @param platform 平台枚举名
     * @param request 用户输入的手机号
     * @param response 用于禁止缓存敏感响应
     * @return 页面进度
     */
    @PostMapping("/{platform}/login/sms-code")
    public PlatformBrowserLoginService.LoginProgress requestSmsCode(
            @PathVariable("platform") String platform, @RequestBody PhoneRequest request,
            HttpServletResponse response) {
        // 禁止缓存操作结果；校验输入后仅交给当前浏览器任务。
        log.info("获取短信验证码 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.requestSmsCode(
                parsePlatform(platform), requireInput(request.phone(), "手机号", 32),
                request.agreedToPlatformTerms());
        log.info("获取短信验证码 response platform={} status={}", platform, progress.status());
        return progress;
    }

    /**
     * 手机号登录：输入手机验证码
     * 把用户收到的短信码送入当前登录页，登录成功仍由进度查询核验。
     *
     * @param platform 平台枚举名
     * @param request 用户输入的短信码
     * @param response 用于禁止缓存敏感响应
     * @return 页面进度
     */
    @PostMapping("/{platform}/login/sms")
    public PlatformBrowserLoginService.LoginProgress submitSmsCode(
            @PathVariable("platform") String platform, @RequestBody SmsCodeRequest request,
            HttpServletResponse response) {
        // 禁止缓存操作结果；验证码只进入当前浏览器任务。
        log.info("提交短信验证码 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.submitSmsCode(
                parsePlatform(platform), requireInput(request.code(), "短信验证码", 16));
        log.info("提交短信验证码 response platform={} status={}", platform, progress.status());
        return progress;
    }

    /**
     * 在原抖音扫码页面选择身份验证方式，返回后续页面截图。
     *
     * @param platform 仅支持抖音
     * @param method 接收验证码或按页面提示发送验证短信
     * @param response 用于禁止缓存验证页面
     * @return 当前验证进度
     */
    @PostMapping("/{platform}/login/identity-verification/{method}")
    public PlatformBrowserLoginService.LoginProgress selectIdentityVerificationMethod(
            @PathVariable("platform") String platform,
            @PathVariable("method") DouyinIdentityVerificationMethod method,
            HttpServletResponse response) {
        log.info("选择抖音身份验证方式 request platform={} method={}", platform, method);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.selectDouyinVerificationMethod(
                parsePlatform(platform), method);
        log.info("选择抖音身份验证方式 response platform={} status={} interactionStage={}",
                platform, progress.status(), progress.interactionStage());
        return progress;
    }

    /**
     * 在原抖音扫码页面提交用户收到的验证码；响应不代表已登录。
     *
     * @param platform 仅支持抖音
     * @param request 用户收到的验证码
     * @param response 用于禁止缓存操作结果
     * @return 当前验证进度
     */
    @PostMapping("/{platform}/login/identity-verification/sms-code")
    public PlatformBrowserLoginService.LoginProgress submitIdentityVerificationCode(
            @PathVariable("platform") String platform, @RequestBody SmsCodeRequest request,
            HttpServletResponse response) {
        log.info("提交抖音身份验证短信码 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.submitDouyinVerificationCode(
                parsePlatform(platform), requireInput(request.code(), "短信验证码", 16));
        log.info("提交抖音身份验证短信码 response platform={} status={} interactionStage={}",
                platform, progress.status(), progress.interactionStage());
        return progress;
    }

    /**
     * 账号密码登录：把账号密码送入当前登录页，不保存或返回密码。
     *
     * @param platform 平台枚举名
     * @param request 本次登录输入
     * @param response 用于禁止缓存敏感响应
     * @return 页面进度
     */
    @PostMapping("/{platform}/login/password")
    public PlatformBrowserLoginService.LoginProgress submitPasswordLogin(
            @PathVariable("platform") String platform, @RequestBody PasswordLoginRequest request,
            HttpServletResponse response) {
        // 禁止缓存操作结果；密码只进入当前浏览器任务。
        log.info("提交账号密码登录 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.submitPasswordLogin(parsePlatform(platform),
                requireInput(request.account(), "账号", 255), requireInput(request.password(), "密码", 255),
                request.agreedToPlatformTerms());
        log.info("提交账号密码登录 response platform={} status={}", platform, progress.status());
        return progress;
    }

    /**
     * 获取扫码进度；登录成功时服务端保存会话。
     *
     * @param platform 平台枚举名
     * @param response 用于禁止缓存敏感响应
     * @return 扫码进度
     */
    @GetMapping("/{platform}/scan/login/result")
    public PlatformBrowserLoginService.LoginProgress readLoginProgress(
            @PathVariable("platform") String platform, HttpServletResponse response) {
        log.debug("查询平台登录进度 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.LoginProgress progress = loginService.readLoginProgress(parsePlatform(platform));
        log.debug("查询平台登录进度 response platform={} status={} interactionStage={}",
                platform, progress.status(), progress.interactionStage());
        return progress;
    }

    /**
     * 取消当前登录尝试，不删除已保存的账号登录态。
     *
     * @param platform 平台枚举名
     * @param response 用于禁止缓存敏感响应
     */
    @PostMapping("/{platform}/login/cancel")
    public void cancelLogin(@PathVariable("platform") String platform, HttpServletResponse response) {
        log.info("取消平台登录 request platform={}", platform);
        disableCaching(response);
        loginService.cancelLogin(parsePlatform(platform));
        log.info("取消平台登录 response platform={} completed=true", platform);
    }

    /**
     * 校验登录状态；用数据库登录态主动访问平台账号接口，更新实际的登录状态。
     *
     * @param platform 平台枚举名
     * @param response 用于禁止缓存敏感响应
     * @return 最新状态
     */
    @PostMapping("/{platform}/check")
    public PlatformBrowserLoginService.PlatformLoginView checkLogin(
            @PathVariable("platform") String platform, HttpServletResponse response) {
        log.info("校验已保存登录态 request platform={}", platform);
        disableCaching(response);
        PlatformBrowserLoginService.PlatformLoginView status = loginService.checkLogin(parsePlatform(platform));
        log.info("校验已保存登录态 response platform={} status={}", platform, status.status());
        return status;
    }

    /**
     * 批量检测数据库中已登录的平台，并更新这些平台的实际登录状态。
     *
     * @param response 用于禁止缓存敏感响应
     * @return 本次检测的平台及最新状态；没有可检测平台时返回空列表
     */
    @PostMapping("/logged-in/check")
    public List<PlatformBrowserLoginService.PlatformLoginView> checkLoggedInPlatforms(HttpServletResponse response) {
        log.info("批量检测已登录平台 request");
        disableCaching(response);
        List<PlatformBrowserLoginService.PlatformLoginView> checked = loginService.checkLoggedInPlatforms();
        log.info("批量检测已登录平台 response checkedCount={} loggedInCount={}", checked.size(),
                checked.stream().filter(platform -> platform.status() == PlatformBrowserLoginStatus.LOGGED_IN).count());
        return checked;
    }

    /**
     * 将独立流程错误转换成可读提示，不输出内部异常或敏感会话。
     *
     * @param exception 登录流程异常
     * @return 失败提示
     */
    @ExceptionHandler(PlatformBrowserLoginException.class)
    public ResponseEntity<Map<String, String>> handleLoginError(PlatformBrowserLoginException exception) {
        log.warn("浏览器登录接口 response httpStatus=400 reason={} causeType={}",
                exception.getMessage(), exception.getCause() == null ? "NONE"
                        : exception.getCause().getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    /**
     * 禁止缓存平台登录状态和扫码截图，避免浏览器保留过期的敏感响应。
     *
     * @param response 当前响应
     */
    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * 只接收已声明的平台枚举，不接受任意站点 URL。
     *
     * @param platform 枚举名称
     * @return 平台类型
     */
    private PublicationPlatformType parsePlatform(String platform) {
        try {
            return PublicationPlatformType.valueOf(platform);
        } catch (IllegalArgumentException exception) {
            throw new PlatformBrowserLoginException("不存在该发布平台", exception);
        }
    }

    /** 将历史知乎 App 参数映射为通用平台 App；拒绝未声明的扫码方式。 */
    private PlatformScanLoginMethod parseScanMethod(String scanMethod) {
        if ("ZHIHU_APP".equals(scanMethod)) return PlatformScanLoginMethod.PLATFORM_APP;
        try {
            return PlatformScanLoginMethod.valueOf(scanMethod);
        } catch (IllegalArgumentException exception) {
            throw new PlatformBrowserLoginException("不支持该扫码方式", exception);
        }
    }

    /**
     * 拒绝空值或过长的登录输入，避免把无效表单交给远端页面。
     *
     * @param value 用户输入
     * @param name 字段名称
     * @param maxLength 长度上限
     * @return 原始输入，密码不会被裁剪
     */
    private String requireInput(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new PlatformBrowserLoginException(name + "不能为空且长度不能超过 " + maxLength);
        }
        return value;
    }

    /** 请求短信码时的手机号；不落库。 */
    public record PhoneRequest(String phone, boolean agreedToPlatformTerms) { }

    /** 用户收到的短信码；不落库。 */
    public record SmsCodeRequest(String code) { }

    /** 当前登录任务使用的账号密码；不落库。 */
    public record PasswordLoginRequest(String account, String password, boolean agreedToPlatformTerms) { }
}
