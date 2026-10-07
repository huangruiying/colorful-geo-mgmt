package org.huangry.colorful.geo.infrastructure.client.login;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformLoginInteractionStage;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * 统一选择平台登录实现；核心入口为打开登录、读取进度与核验会话。
 * 页面操作和账号接口解析由各平台浏览器客户端负责，这里不保存账号或会话。
 */
@Component
@RequiredArgsConstructor
public class PlatformBrowserLoginRouter {

    private final QrPlatformPlaywrightLoginClient qrClient;
    private final FormPlatformPlaywrightLoginClient formClient;
    private final PlatformThirdPartyLoginClient thirdPartyClient;

    /**
     * 仅开放具备登录页和账号核验实现的平台。
     *
     * @param platform 目标平台
     * @return 是否支持独立浏览器登录
     */
    public boolean supportsPlatform(PublicationPlatformType platform) {
        return QrPlatformPlaywrightLoginClient.supportsPlatform(platform)
                || FormPlatformPlaywrightLoginClient.supportsPlatform(platform);
    }

    /**
     * 打开目标平台的指定登录入口；切换入口时释放同平台旧授权页面。
     *
     * @param platform 目标平台
     * @param scanMethod 平台 App、微信或 QQ 扫码方式
     * @param agreedToPlatformTerms 用户是否同意目标平台协议
     * @return 可供页面展示的登录进度
     */
    public LoginSnapshot startLogin(PublicationPlatformType platform, PlatformScanLoginMethod scanMethod,
                                    boolean agreedToPlatformTerms) {
        requireSupported(platform);
        // 1. 原生扫码入口由二维码客户端处理，包括知乎 App 和原站微信二维码。
        if (qrClient.handlesScanMethod(platform, scanMethod)) {
            thirdPartyClient.cancelLogin(platform);
            if (FormPlatformPlaywrightLoginClient.supportsPlatform(platform)) formClient.cancelLogin(platform);
            return snapshot(platform == PublicationPlatformType.CSDN
                    && scanMethod == PlatformScanLoginMethod.WECHAT
                    ? qrClient.startCsdnWechatLogin() : qrClient.startLogin(platform));
        }
        // 2. 其他微信/QQ 入口交给第三方授权，原生登录任务必须先结束。
        if (scanMethod == PlatformScanLoginMethod.WECHAT || scanMethod == PlatformScanLoginMethod.QQ) {
            cancelNativeLogin(platform);
            return snapshot(thirdPartyClient.startLogin(platform, scanMethod, agreedToPlatformTerms));
        }
        // 3. 原生入口关闭旧授权页，再选择表单或二维码客户端。
        thirdPartyClient.cancelLogin(platform);
        if (QrPlatformPlaywrightLoginClient.supportsPlatform(platform)) qrClient.cancelLogin(platform);
        if (platform == PublicationPlatformType.BAIJIAHAO
                && scanMethod == PlatformScanLoginMethod.PLATFORM_APP) {
            return snapshot(formClient.startBaijiahaoQrLogin());
        }
        return FormPlatformPlaywrightLoginClient.supportsPlatform(platform)
                ? snapshot(formClient.startLogin(platform)) : snapshot(qrClient.startLogin(platform));
    }

    /**
     * 在当前平台表单中请求短信码，账号结论仍由进度查询取得。
     *
     * @param platform 目标平台
     * @param phone 手机号
     * @param agreedToPlatformTerms 用户是否同意平台协议
     * @return 当前页面进度
     */
    public LoginSnapshot requestSmsCode(PublicationPlatformType platform, String phone,
                                        boolean agreedToPlatformTerms) {
        requireSupported(platform);
        return snapshot(formClient.requestSmsCode(platform, phone, agreedToPlatformTerms));
    }

    /**
     * 将短信码送入当前平台表单，不据此认定账号已登录。
     *
     * @param platform 目标平台
     * @param code 本次短信验证码
     * @return 当前页面进度
     */
    public LoginSnapshot submitSmsCode(PublicationPlatformType platform, String code) {
        requireSupported(platform);
        return snapshot(formClient.submitSmsCode(platform, code));
    }

    /**
     * 抖音扫码后选择页面提供的身份验证方式，不切换到新的登录会话。
     *
     * @param platform 当前平台，只接受抖音
     * @param method 用户选择的验证方式
     * @return 当前验证页进度
     */
    public LoginSnapshot selectDouyinVerificationMethod(PublicationPlatformType platform,
                                                        DouyinIdentityVerificationMethod method) {
        requireDouyinPlatform(platform);
        return snapshot(qrClient.selectDouyinVerificationMethod(method));
    }

    /**
     * 将短信码提交到抖音当前扫码会话，不直接认定登录成功。
     *
     * @param platform 当前平台，只接受抖音
     * @param code 用户收到的验证码
     * @return 当前验证页进度
     */
    public LoginSnapshot submitDouyinVerificationCode(PublicationPlatformType platform, String code) {
        requireDouyinPlatform(platform);
        return snapshot(qrClient.submitDouyinVerificationCode(code));
    }

    /** 二次验证只能操作抖音，避免通过通用路由误操作其他平台会话。 */
    private void requireDouyinPlatform(PublicationPlatformType platform) {
        if (platform != PublicationPlatformType.DOUYIN) {
            throw new PlatformBrowserLoginException("仅抖音扫码登录支持此身份验证操作");
        }
    }

    /**
     * 将账号密码送入本次隔离浏览器，不保存输入凭据。
     *
     * @param platform 目标平台
     * @param account 用户输入的账号
     * @param password 本次密码
     * @param agreedToPlatformTerms 用户是否同意平台协议
     * @return 当前页面进度
     */
    public LoginSnapshot submitPasswordLogin(PublicationPlatformType platform, String account, String password,
                                             boolean agreedToPlatformTerms) {
        requireSupported(platform);
        return snapshot(formClient.submitPasswordLogin(platform, account, password, agreedToPlatformTerms));
    }

    /**
     * 查询当前登录任务；第三方授权页面优先于原生入口返回结果。
     *
     * @param platform 目标平台
     * @return 账号核验结论及仅供服务层保存的会话
     */
    public LoginSnapshot readLoginProgress(PublicationPlatformType platform) {
        requireSupported(platform);
        // 只有平台账号接口或抖音创作者主页明确提供账号标识时，底层才附带可保存的会话。
        if (thirdPartyClient.isActiveFor(platform)) return snapshot(thirdPartyClient.readLoginProgress(platform));
        if (qrClient.isActiveFor(platform)) return snapshot(qrClient.readLoginProgress(platform));
        return FormPlatformPlaywrightLoginClient.supportsPlatform(platform)
                ? snapshot(formClient.readLoginProgress(platform))
                : snapshot(qrClient.readLoginProgress(platform));
    }

    /**
     * 恢复已保存的浏览器会话，并由平台账号接口或抖音创作者主页核验身份。
     *
     * @param platform 会话所属平台
     * @param storageState 数据库中保存的 Playwright 会话
     * @return 明确已登录、明确未登录或未知
     */
    public AccountProbe verifySavedLogin(PublicationPlatformType platform, String storageState) {
        requireSupported(platform);
        if (FormPlatformPlaywrightLoginClient.supportsPlatform(platform)) {
            FormPlatformPlaywrightLoginClient.AccountProbe probe = formClient.verifySavedLogin(platform, storageState);
            return new AccountProbe(probe.status(), probe.accountName());
        }
        QrPlatformPlaywrightLoginClient.AccountProbe probe = qrClient.verifySavedLogin(platform, storageState);
        return new AccountProbe(probe.status(), probe.accountName());
    }

    /**
     * 取消指定平台的本次登录任务，不删除已保存的会话。
     *
     * @param platform 目标平台
     */
    public void cancelLogin(PublicationPlatformType platform) {
        requireSupported(platform);
        thirdPartyClient.cancelLogin(platform);
        cancelNativeLogin(platform);
    }

    /**
     * 根据已实现的平台入口列出可操作方式，供页面渲染按钮。
     *
     * @param platform 目标平台
     * @return 可用登录方式；支持扫码时优先返回扫码，供页面默认选中
     */
    public List<String> loginMethods(PublicationPlatformType platform) {
        if (FormPlatformPlaywrightLoginClient.supportsPlatform(platform)) {
            boolean hasQr = platform == PublicationPlatformType.BAIJIAHAO
                    || QrPlatformPlaywrightLoginClient.supportsPlatform(platform)
                    || !PlatformThirdPartyLoginClient.listScanMethods(platform).isEmpty();
            if (!FormPlatformPlaywrightLoginClient.supportsPasswordLogin(platform)) {
                if (!FormPlatformPlaywrightLoginClient.supportsSmsLogin(platform)) {
                    return hasQr ? List.of("qr") : List.of();
                }
                return hasQr ? List.of("qr", "sms") : List.of("sms");
            }
            if (hasQr) return FormPlatformPlaywrightLoginClient.supportsSmsLogin(platform)
                    ? List.of("qr", "sms", "password") : List.of("qr", "password");
            return FormPlatformPlaywrightLoginClient.supportsSmsLogin(platform)
                    ? List.of("sms", "password") : List.of("password");
        }
        return QrPlatformPlaywrightLoginClient.supportsPlatform(platform) ? List.of("qr") : List.of();
    }

    /**
     * 列出平台已有的扫码来源，页面不展示尚未实现的第三方按钮。
     *
     * @param platform 目标平台
     * @return 可选扫码来源
     */
    public List<PlatformScanLoginMethod> scanMethods(PublicationPlatformType platform) {
        if (platform == PublicationPlatformType.CSDN || platform == PublicationPlatformType.SMZDM) {
            return List.of(PlatformScanLoginMethod.PLATFORM_APP,
                    PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ);
        }
        if (platform == PublicationPlatformType.BAIJIAHAO) return List.of(PlatformScanLoginMethod.PLATFORM_APP);
        if (QrPlatformPlaywrightLoginClient.supportsPlatform(platform)) {
            return Stream.concat(Stream.of(PlatformScanLoginMethod.PLATFORM_APP),
                    PlatformThirdPartyLoginClient.listScanMethods(platform).stream()).toList();
        }
        return PlatformThirdPartyLoginClient.listScanMethods(platform);
    }

    /**
     * 拒绝缺少登录页或账号核验器的平台，避免展示无法执行的登录操作。
     *
     * @param platform 待检查的平台
     */
    public void requireSupported(PublicationPlatformType platform) {
        if (!supportsPlatform(platform)) throw new PlatformBrowserLoginException("该平台尚未接入独立浏览器登录");
    }

    /**
     * 切换第三方授权前关闭原生登录页，避免同平台并存两个会话。
     *
     * @param platform 当前登录平台
     */
    private void cancelNativeLogin(PublicationPlatformType platform) {
        if (FormPlatformPlaywrightLoginClient.supportsPlatform(platform)) formClient.cancelLogin(platform);
        if (QrPlatformPlaywrightLoginClient.supportsPlatform(platform)) qrClient.cancelLogin(platform);
    }

    /** 将扫码客户端结果转为服务层统一结果。 */
    private LoginSnapshot snapshot(QrPlatformPlaywrightLoginClient.BrowserLoginSnapshot value) {
        return new LoginSnapshot(value.status(), value.accountName(), value.screenshotBase64(),
                value.storageState(), value.interactionStage());
    }

    /** 将表单平台内部会话转为平台通用结果。 */
    private LoginSnapshot snapshot(FormPlatformPlaywrightLoginClient.BrowserLoginSnapshot value) {
        return new LoginSnapshot(value.status(), value.accountName(), value.screenshotBase64(), value.storageState(), null);
    }

    /** 将第三方授权内部会话转为平台通用结果。 */
    private LoginSnapshot snapshot(PlatformThirdPartyLoginClient.BrowserLoginSnapshot value) {
        return new LoginSnapshot(value.status(), value.accountName(), value.screenshotBase64(),
                value.storageState(), value.interactionStage());
    }

    /** 登录进度内部结果；storageState 只能流向落库流程，不能作为接口响应。 */
    public record LoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName, String screenshotBase64,
                                String storageState, PlatformLoginInteractionStage interactionStage) { }

    /** 平台账号接口或抖音创作者主页给出的登录结论。 */
    public record AccountProbe(PlatformBrowserLoginStatus status, String accountName) { }
}
