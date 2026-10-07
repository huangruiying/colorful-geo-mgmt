package org.huangry.colorful.geo.domain.service.login;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.login.PlatformBrowserLoginRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformLoginInteractionStage;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 独立浏览器登录服务。已接入平台确认账号后保存会话；不改旧发布与登录通道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformBrowserLoginService {

    private final PlatformBrowserLoginDao loginDao;
    private final PlatformBrowserLoginRouter loginRouter;

    /**
     * 展示枚举中的所有平台及当前数据库记录；未接入平台不伪装为未登录。
     *
     * @return 平台列表
     */
    public List<PlatformLoginView> listPlatforms() {
        return Arrays.stream(PublicationPlatformType.values()).map(platform -> {
            if (!loginRouter.supportsPlatform(platform)) {
                return new PlatformLoginView(platform.name(), platform.getDisplayName(), false,
                        PlatformBrowserLoginStatus.NOT_SUPPORTED, null, null, false, List.of(), List.of());
            }
            PlatformBrowserLoginEntity saved = loginDao.findByPlatform(platform.name());
            return saved == null || !hasUsableStorageState(saved)
                    ? new PlatformLoginView(platform.name(), platform.getDisplayName(), true,
                            saved == null ? PlatformBrowserLoginStatus.NOT_LOGGED_IN : PlatformBrowserLoginStatus.EXPIRED,
                            saved == null ? null : saved.getAccountName(),
                            saved == null ? null : saved.getLastCheckedAt(), false,
                            loginRouter.loginMethods(platform), loginRouter.scanMethods(platform))
                    : new PlatformLoginView(platform.name(), platform.getDisplayName(), true,
                            PlatformBrowserLoginStatus.valueOf(saved.getLoginStatus()),
                            saved.getAccountName(), saved.getLastCheckedAt(), true,
                            loginRouter.loginMethods(platform), loginRouter.scanMethods(platform));
        }).toList();
    }

    /**
     * 打开目标平台登录页；扫码和表单平台分别由对应浏览器客户端维护。
     *
     * @param platform 平台枚举
     * @param scanMethod 平台 App、微信或 QQ 扫码方式；表单登录可不传
     * @param agreedToPlatformTerms 用户是否同意目标平台协议
     * @return 登录页截图及任务状态
     */
    public LoginProgress startLogin(PublicationPlatformType platform, PlatformScanLoginMethod scanMethod,
                                    boolean agreedToPlatformTerms) {
        return publicProgress(loginRouter.startLogin(platform, scanMethod, agreedToPlatformTerms));
    }

    /**
     * 在当前平台登录页请求短信验证码，不保存手机号或验证码。
     *
     * @param platform 目标平台
     * @param phone 手机号
     * @param agreedToPlatformTerms 用户是否同意目标平台协议
     * @return 页面进度与截图
     */
    public LoginProgress requestSmsCode(PublicationPlatformType platform, String phone,
                                        boolean agreedToPlatformTerms) {
        // 发码只操作当前浏览器页，不代表已通过平台账号核验。
        return publicProgress(loginRouter.requestSmsCode(platform, phone, agreedToPlatformTerms));
    }

    /**
     * 将短信码送入当前平台登录页；成功后的会话由进度查询统一保存。
     *
     * @param platform 目标平台
     * @param code 短信码
     * @return 页面进度与截图
     */
    public LoginProgress submitSmsCode(PublicationPlatformType platform, String code) {
        // 提交短信码不代表已登录，必须继续等待目标平台账号接口确认。
        return publicProgress(loginRouter.submitSmsCode(platform, code));
    }

    /**
     * 在原抖音扫码会话中选择二次验证方式，不新建会话或保存未核验的登录态。
     *
     * @param platform 目标平台
     * @param method 用户选择的方式
     * @return 当前验证页进度
     */
    public LoginProgress selectDouyinVerificationMethod(PublicationPlatformType platform,
                                                        DouyinIdentityVerificationMethod method) {
        return publicProgress(loginRouter.selectDouyinVerificationMethod(platform, method));
    }

    /**
     * 把抖音二次验证短信码交给原扫码会话，登录结论仍由进度查询落库。
     *
     * @param platform 目标平台
     * @param code 用户收到的验证码
     * @return 当前验证页进度
     */
    public LoginProgress submitDouyinVerificationCode(PublicationPlatformType platform, String code) {
        return publicProgress(loginRouter.submitDouyinVerificationCode(platform, code));
    }

    /**
     * 将用户输入的账号密码送入当前平台登录页，不在服务层保存凭据。
     *
     * @param platform 目标平台
     * @param account 手机号或邮箱
     * @param password 登录密码
     * @param agreedToPlatformTerms 用户是否同意目标平台协议
     * @return 页面进度与截图
     */
    public LoginProgress submitPasswordLogin(PublicationPlatformType platform, String account, String password,
                                             boolean agreedToPlatformTerms) {
        // 密码只送入本次隔离浏览器；数据库仍仅保存经账号核验的会话。
        return publicProgress(loginRouter.submitPasswordLogin(platform, account, password, agreedToPlatformTerms));
    }

    /**
     * 查询目标平台登录进度；账号接口或抖音创作者主页确认身份后才保存会话。
     *
     * @param platform 平台枚举
     * @return 进度及可选页面截图
     */
    public LoginProgress readLoginProgress(PublicationPlatformType platform) {
        PlatformBrowserLoginRouter.LoginSnapshot snapshot = loginRouter.readLoginProgress(platform);
        saveSuccessfulLogin(platform, snapshot.status(), snapshot.accountName(), snapshot.storageState());
        return publicProgress(snapshot);
    }

    /**
     * 仅平台明确取得账号及会话时写入数据库；未完成扫码不覆盖原有登录态。
     *
     * @param platform 登录平台
     * @param status 本次登录任务的结果；只有已登录才保存账号会话
     * @param accountName 平台账号名
     * @param storageState 当前浏览器会话
     */
    private void saveSuccessfulLogin(PublicationPlatformType platform, PlatformBrowserLoginTaskStatus status,
                                     String accountName, String storageState) {
        if (status == PlatformBrowserLoginTaskStatus.LOGGED_IN) {
            loginDao.saveSuccessfulLogin(platform.name(), accountName, storageState);
            log.info("Playwright 平台登录成功，platformType={}", platform.name());
        }
    }

    /**
     * 在新的隔离上下文里恢复数据库登录态并核验，更新列表的最新结论。
     *
     * @param platform 平台枚举
     * @return 最新平台状态
     */
    public PlatformLoginView checkLogin(PublicationPlatformType platform) {
        loginRouter.requireSupported(platform);
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform(platform.name());
        if (saved == null) {
            return new PlatformLoginView(platform.name(), platform.getDisplayName(), true,
                    PlatformBrowserLoginStatus.NOT_LOGGED_IN, null, null, false,
                    loginRouter.loginMethods(platform), loginRouter.scanMethods(platform));
        }
        // 旧版本的密文不能作为 Playwright JSON 使用；保留记录并提示重新扫码。
        if (!hasUsableStorageState(saved)) {
            return new PlatformLoginView(platform.name(), platform.getDisplayName(), true,
                    PlatformBrowserLoginStatus.EXPIRED, saved.getAccountName(), saved.getLastCheckedAt(), false,
                    loginRouter.loginMethods(platform), loginRouter.scanMethods(platform));
        }
        // 在新浏览器上下文验证；401 才是明确过期，网络或风控失败为未知。
        // 按平台核验账号，抖音还可核对主页抖音号；旧 Cookie 或单纯跳转均不算成功。
        PlatformBrowserLoginRouter.AccountProbe account = loginRouter.verifySavedLogin(platform, saved.getStorageState());
        PlatformBrowserLoginStatus status = switch (account.status()) {
            case LOGGED_IN -> PlatformBrowserLoginStatus.LOGGED_IN;
            case NOT_LOGGED_IN -> PlatformBrowserLoginStatus.EXPIRED;
            default -> PlatformBrowserLoginStatus.UNKNOWN;
        };
        loginDao.updateCheckedStatus(platform.name(), status.name(), account.accountName());
        log.info("Playwright 平台登录态核验，platformType={}，status={}", platform.name(), status);
        return new PlatformLoginView(platform.name(), platform.getDisplayName(), true,
                status, account.accountName() == null ? saved.getAccountName() : account.accountName(),
                LocalDateTime.now(), true, loginRouter.loginMethods(platform), loginRouter.scanMethods(platform));
    }

    /**
     * 批量核验数据库中已登录且已接入的平台；未登录、已失效和未接入平台不访问第三方网站。
     *
     * @return 本次实际核验的平台及最新状态
     */
    public List<PlatformLoginView> checkLoggedInPlatforms() {
        // 先读取已保存的登录结论，只把具备可用会话的已登录平台交给单平台核验流程。
        return listPlatforms().stream()
                .filter(platform -> platform.supported() && platform.status() == PlatformBrowserLoginStatus.LOGGED_IN)
                .map(platform -> checkLogin(PublicationPlatformType.valueOf(platform.platformType())))
                .toList();
    }

    /**
     * 取消当前登录任务，不删除已有数据库会话。
     *
     * @param platform 平台枚举
     */
    public void cancelLogin(PublicationPlatformType platform) {
        loginRouter.cancelLogin(platform);
    }

    /**
     * 识别可恢复的 Playwright JSON；迁移前保存的密文需重新扫码覆盖。
     *
     * @param saved 当前平台的数据库记录
     * @return 是否存在可恢复的明文会话
     */
    private boolean hasUsableStorageState(PlatformBrowserLoginEntity saved) {
        String storageState = saved.getStorageState();
        return storageState != null && storageState.stripLeading().startsWith("{");
    }

    /**
     * 过滤内部 storageState，页面仅能拿到短时效的登录页截图。
     *
     * @param snapshot 内部浏览器结果
     * @return 页面进度
     */
    private LoginProgress publicProgress(PlatformBrowserLoginRouter.LoginSnapshot snapshot) {
        return new LoginProgress(snapshot.status(), snapshot.accountName(), snapshot.screenshotBase64(),
                snapshot.interactionStage());
    }

    /** 页面所见的平台状态；validatedLogin 仅表示本部署曾保存真实账号核验后的可恢复会话。 */
    public record PlatformLoginView(String platformType, String platformName, boolean supported,
                                    PlatformBrowserLoginStatus status, String accountName,
                                    LocalDateTime lastCheckedAt, boolean validatedLogin,
                                    List<String> loginMethods, List<PlatformScanLoginMethod> scanMethods) { }

    /** 页面所见的短期登录任务进度，与数据库账号登录状态分开。 */
    public record LoginProgress(PlatformBrowserLoginTaskStatus status, String accountName,
                                String screenshotBase64, PlatformLoginInteractionStage interactionStage) { }
}
