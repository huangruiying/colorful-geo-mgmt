package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.BrowserTaskRunner;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短信验证码、账号密码登录
 * 管理账号密码和短信验证码登录页；核心入口是打开页面、提交表单和核验账号。
 * 只在平台账号响应明确给出身份时导出会话，不接管内容发布。
 */
@Component
@RequiredArgsConstructor
public class FormPlatformPlaywrightLoginClient {

    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    private static final Pattern CNBLOGS_ACCOUNT = Pattern.compile("href=\"/u/([^/\"]+)/\"");
    private static final Pattern SEGMENTFAULT_ACCOUNT = Pattern.compile("href=\"/u/([^\"]+)\"");
    private static final Pattern WOSHIPM_ACCOUNT = Pattern.compile(
            "var\\s+userSettings\\s*=\\s*\\{[^}]*\"uid\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern YUQUE_APP_DATA = Pattern.compile(
            "window\\.appData\\s*=\\s*JSON\\.parse\\(decodeURIComponent\\(\"([^\"]+)\"\\)\\)");
    private static final Map<PublicationPlatformType, FormLoginSite> LOGIN_SITES = Map.ofEntries(
            Map.entry(PublicationPlatformType.ZHIHU, new FormLoginSite(
                    "https://www.zhihu.com/signin", "https://www.zhihu.com/api/v4/me", true, true)),
            Map.entry(PublicationPlatformType.JIANSHU, new FormLoginSite(
                    "https://www.jianshu.com/sign_in", "https://www.jianshu.com/settings/basic.json", false, true)),
            Map.entry(PublicationPlatformType.CNBLOGS, new FormLoginSite(
                    "https://account.cnblogs.com/signin", "https://home.cnblogs.com/user/CurrentUserInfo", true, true)),
            Map.entry(PublicationPlatformType.BAIJIAHAO, new FormLoginSite(
                    "https://baijiahao.baidu.com/builder/theme/bjh/login",
                    "https://baijiahao.baidu.com/builder/app/appinfo", true, true)),
            Map.entry(PublicationPlatformType.OSCHINA, new FormLoginSite(
                    "https://www.oschina.net/home/login",
                    "https://apiv1.oschina.net/oschinapi/user/myDetails", true, true)),
            Map.entry(PublicationPlatformType.SEGMENTFAULT, new FormLoginSite(
                    "https://segmentfault.com/user/login",
                    "https://segmentfault.com/user/settings", true, false)),
            Map.entry(PublicationPlatformType.IMOOC, new FormLoginSite(
                    "https://www.imooc.com/user/newlogin",
                    "https://www.imooc.com/u/card", true, true)),
            Map.entry(PublicationPlatformType.NETEASE, new FormLoginSite(
                    "https://mp.163.com/login.html",
                    "https://mp.163.com/wemedia/navinfo.do", true, true)),
            Map.entry(PublicationPlatformType.WOSHIPM, new FormLoginSite(
                    "https://www.woshipm.com/wp-login.php",
                    "https://www.woshipm.com/writing", false, true)),
            Map.entry(PublicationPlatformType.SOHU, new FormLoginSite(
                    "https://mp.sohu.com/", "https://mp.sohu.com/mpbp/bp/account/list", false, true)),
            Map.entry(PublicationPlatformType.YUQUE, new FormLoginSite(
                    "https://www.yuque.com/login", "https://www.yuque.com/dashboard", false, false)),
            Map.entry(PublicationPlatformType.YIDIAN, new FormLoginSite(
                    "https://mp.yidianzixun.com/", "https://mp.yidianzixun.com/api/refact-get-main-data2",
                    false, false)));

    private final ObjectMapper objectMapper;
    private final PlaywrightBrowserComponent browserComponent;
    private final BrowserTaskRunner taskRunner = new BrowserTaskRunner("form-platform-login-browser");

    /** 浏览器线程异常统一映射为平台登录异常。 */
    private static final Function<Throwable, RuntimeException> LOGIN_FAILURE = cause -> {
        if (cause instanceof InterruptedException) {
            return new PlatformBrowserLoginException("平台浏览器登录操作被中断", cause);
        }
        if (cause instanceof TimeoutException) {
            return new PlatformBrowserLoginException("平台浏览器登录操作超时", cause);
        }
        return new PlatformBrowserLoginException("平台浏览器登录操作失败", cause);
    };
    private PlaywrightBrowserComponent.BrowserSession activeSession;
    private BrowserContext activeContext;
    private Page activePage;
    private volatile PublicationPlatformType activePlatform;
    private Instant startedAt;
    private boolean activeQrLogin;

    /**
     * 判断目标平台是否已有登录页与独立账号核验规则。
     *
     * @param platform 目标平台
     * @return 是否可打开表单登录
     */
    public static boolean supportsPlatform(PublicationPlatformType platform) {
        return LOGIN_SITES.containsKey(platform);
    }

    /** 返回表单平台已核对的登录入口，供同平台第三方授权复用。 */
    static String loginUrl(PublicationPlatformType platform) {
        FormLoginSite site = LOGIN_SITES.get(platform);
        if (site == null) throw new PlatformBrowserLoginException("该平台尚未接入表单登录");
        return site.loginUrl();
    }

    /**
     * 判断目标平台能否发送短信验证码，供页面隐藏不适用的入口。
     *
     * @param platform 目标平台
     * @return 是否有短信登录表单
     */
    public static boolean supportsSmsLogin(PublicationPlatformType platform) {
        FormLoginSite site = LOGIN_SITES.get(platform);
        return site != null && site.smsLogin();
    }

    /**
     * 判断平台密码表单是否已核对并接入。
     *
     * @param platform 目标平台
     * @return 是否可提交密码
     */
    public static boolean supportsPasswordLogin(PublicationPlatformType platform) {
        FormLoginSite site = LOGIN_SITES.get(platform);
        return site != null && site.passwordLogin();
    }

    /**
     * 打开表单登录页，后续密码或短信操作复用这次浏览器会话。
     *
     * @param platform 目标平台
     * @return 等待用户输入的状态
     */
    public BrowserLoginSnapshot startLogin(PublicationPlatformType platform) {
        FormLoginSite site = requireSite(platform);
        return onBrowserThread(() -> {
            closeActiveLogin();
            try {
                // 1. 创建隔离浏览器，不借用本机 Chrome 的登录态。
                activeSession = browserComponent.openBrowser(
                        new Browser.NewContextOptions().setViewportSize(900, 720));
                activeContext = activeSession.context();
                activePage = activeSession.newPage();
                activePage.setDefaultTimeout(15_000);
                // 2. 打开平台登录页，保留本次会话供后续表单操作。
                activePage.navigate(site.loginUrl(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
                if (platform == PublicationPlatformType.BAIJIAHAO) {
                    activePage.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("登录/注册百家号")).click();
                }
                if (platform == PublicationPlatformType.SOHU) {
                    activePage.getByText("登录/注册", new Page.GetByTextOptions().setExact(true)).click();
                }
                activePlatform = platform;
                startedAt = Instant.now();
                Instant loginStartedAt = startedAt;
                taskRunner.schedule(() -> {
                    if (loginStartedAt.equals(startedAt)) closeActiveLogin();
                }, LOGIN_LIFETIME.toSeconds(), TimeUnit.SECONDS);
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                closeActiveLogin();
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "登录页打开失败，请检查网络", exception);
            }
        });
    }

    /**
     * 打开百家号登录弹窗中的百度 App 二维码；仍使用百家号账号接口确认扫码结果。
     *
     * @return 等待扫码的图片和进度
     */
    public BrowserLoginSnapshot startBaijiahaoQrLogin() {
        startLogin(PublicationPlatformType.BAIJIAHAO);
        return onBrowserThread(() -> {
            activeQrLogin = true;
            activePage.locator("img.tang-pass-qrcode-img").waitFor();
            return pendingSnapshot();
        });
    }

    /**
     * 在当前平台登录页提交密码，不保存账号密码。
     *
     * @param platform 目标平台
     * @param account 用户输入的账号
     * @param password 本次登录密码
     * @param agreedToPlatformTerms 用户是否已阅读并同意目标平台协议
     * @return 等待账号核验的状态
     */
    public BrowserLoginSnapshot submitPasswordLogin(PublicationPlatformType platform, String account, String password,
                                                    boolean agreedToPlatformTerms) {
        requirePasswordSite(platform);
        return onBrowserThread(() -> {
            requireActivePage(platform);
            try {
                // 平台表单字段不同，提交后仍统一等待账号接口确认。
                if (platform == PublicationPlatformType.ZHIHU) {
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("密码登录")).click();
                    activePage.getByPlaceholder("手机号或邮箱").fill(account);
                    activePage.getByPlaceholder("密码").fill(password);
                } else if (platform == PublicationPlatformType.JIANSHU) {
                    activePage.getByPlaceholder("手机号或邮箱").fill(account);
                    activePage.getByPlaceholder("密码").fill(password);
                } else if (platform == PublicationPlatformType.CNBLOGS) {
                    activePage.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("密码登录")).click();
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("登录用户名 / 邮箱")).fill(account);
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("密码")).fill(password);
                } else if (platform == PublicationPlatformType.BAIJIAHAO) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByText("百度账号登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("手机号/用户名/邮箱")).fill(account);
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("密码")).fill(password);
                    acceptPlatformAgreement(platform);
                } else if (platform == PublicationPlatformType.IMOOC) {
                    activePage.getByText("账号登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByPlaceholder("请输入登录手机号/邮箱").fill(account);
                    activePage.getByPlaceholder("请输入密码").fill(password);
                } else if (platform == PublicationPlatformType.NETEASE) {
                    FrameLocator loginFrame = activePage.frameLocator("iframe[id^='x-URS-iframe']");
                    loginFrame.getByText("邮箱登录", new FrameLocator.GetByTextOptions().setExact(true)).click();
                    loginFrame.getByPlaceholder("网易邮箱号").fill(account);
                    loginFrame.getByPlaceholder("密码").fill(password);
                    loginFrame.getByRole(AriaRole.LINK,
                            new FrameLocator.GetByRoleOptions().setName("登录 / 注册")).click();
                    return pendingSnapshot();
                } else if (platform == PublicationPlatformType.WOSHIPM) {
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("用户名或邮箱地址")).fill(account);
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("密码")).fill(password);
                } else if (platform == PublicationPlatformType.SOHU) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByText("账号登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByPlaceholder("请输入邮箱/手机号").fill(account);
                    activePage.getByPlaceholder("请输入密码").fill(password);
                    acceptPlatformAgreement(platform);
                } else {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("密码登录")).click();
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("手机号/用户名/邮箱")).fill(account);
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("密码")).fill(password);
                    acceptPlatformAgreement(platform);
                }
                String buttonName = platform == PublicationPlatformType.OSCHINA ? "登 录" : "登录";
                activePage.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(buttonName).setExact(true)).click();
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "密码登录操作失败，请查看页面提示", exception);
            }
        });
    }

    /**
     * 在支持短信的平台页面填写手机号并请求验证码；手机号仅留在当前浏览器页。
     *
     * @param platform 目标平台
     * @param phone 手机号
     * @param agreedToPlatformTerms 用户是否已阅读并同意目标平台协议
     * @return 等待验证码的状态
     */
    public BrowserLoginSnapshot requestSmsCode(PublicationPlatformType platform, String phone,
                                               boolean agreedToPlatformTerms) {
        requireSmsSite(platform);
        return onBrowserThread(() -> {
            requireActivePage(platform);
            try {
                if (platform == PublicationPlatformType.ZHIHU) {
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("验证码登录")).click();
                    activePage.getByPlaceholder("手机号").fill(phone);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("获取短信验证码")).click();
                } else if (platform == PublicationPlatformType.CNBLOGS) {
                    activePage.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("短信登录")).click();
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("手机号")).fill(phone);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("获取验证码")).click();
                } else if (platform == PublicationPlatformType.BAIJIAHAO) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByText("短信快捷登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("手机号")).fill(phone);
                    acceptPlatformAgreement(platform);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发送验证码")).click();
                } else if (platform == PublicationPlatformType.OSCHINA) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("免密登录")).click();
                    activePage.getByRole(AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("手机号码")).fill(phone);
                    acceptPlatformAgreement(platform);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发送验证码")).click();
                } else if (platform == PublicationPlatformType.SEGMENTFAULT) {
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("手机号")).fill(phone);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("获取验证码")).click();
                } else if (platform == PublicationPlatformType.NETEASE) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    FrameLocator loginFrame = activePage.frameLocator("iframe[id^='x-URS-iframe']");
                    loginFrame.getByText("手机号登录", new FrameLocator.GetByTextOptions().setExact(true)).click();
                    loginFrame.getByPlaceholder("请输入手机号").fill(phone);
                    acceptPlatformAgreement(platform);
                    loginFrame.getByRole(AriaRole.LINK,
                            new FrameLocator.GetByRoleOptions().setName("获取验证码")).click();
                } else {
                    activePage.getByText("验证码登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByPlaceholder("短信登录仅限中国大陆用户").fill(phone);
                    activePage.getByText("获取验证码", new Page.GetByTextOptions().setExact(true)).click();
                }
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                String reason = platform == PublicationPlatformType.OSCHINA
                        ? "获取验证码未完成；开源中国可能要求人机验证，当前窗口暂不能操作，请尝试密码登录"
                        : "获取验证码失败，请查看页面提示";
                throw new PlatformBrowserLoginException(platform.getDisplayName() + reason, exception);
            }
        });
    }

    /**
     * 在当前平台页面提交短信码，账号身份仍由进度查询确认。
     *
     * @param platform 目标平台
     * @param code 本次短信码
     * @return 等待账号核验的状态
     */
    public BrowserLoginSnapshot submitSmsCode(PublicationPlatformType platform, String code) {
        requireSmsSite(platform);
        return onBrowserThread(() -> {
            requireActivePage(platform);
            try {
                String codeName = switch (platform) {
                    case OSCHINA -> "短信验证码";
                    case SEGMENTFAULT -> "请输入验证码";
                    case IMOOC -> "请输入短信验证码";
                    case NETEASE -> "请输入短信验证码";
                    default -> "验证码";
                };
                if (platform == PublicationPlatformType.ZHIHU) {
                    activePage.getByPlaceholder("输入 6 位短信验证码").fill(code);
                    activePage.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("登录/注册").setExact(true)).click();
                    return pendingSnapshot();
                }
                if (platform == PublicationPlatformType.NETEASE) {
                    FrameLocator loginFrame = activePage.frameLocator("iframe[id^='x-URS-iframe']");
                    loginFrame.getByPlaceholder(codeName).fill(code);
                    loginFrame.getByText("登录", new FrameLocator.GetByTextOptions().setExact(true)).click();
                    return pendingSnapshot();
                }
                if (platform == PublicationPlatformType.IMOOC) activePage.getByPlaceholder(codeName).fill(code);
                else activePage.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName(codeName)).fill(code);
                if (platform == PublicationPlatformType.BAIJIAHAO) acceptPlatformAgreement(platform);
                String buttonName = platform == PublicationPlatformType.OSCHINA ? "登录/注册" : "登录";
                activePage.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(buttonName).setExact(true)).click();
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "短信登录操作失败，请查看页面提示", exception);
            }
        });
    }

    /**
     * 查询当前表单登录结果；仅平台账号接口确认身份后导出会话。
     *
     * @param platform 当前登录平台
     * @return 登录结论及内部会话
     */
    public BrowserLoginSnapshot readLoginProgress(PublicationPlatformType platform) {
        requireSite(platform);
        return onBrowserThread(() -> {
            if (activeContext == null || activePlatform != platform) {
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.NOT_STARTED, null, null);
            }
            if (startedAt.plus(LOGIN_LIFETIME).isBefore(Instant.now())) {
                closeActiveLogin();
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.EXPIRED, null, null);
            }
            AccountProbe probe = probeAccount(platform, activeContext);
            if (probe.status() == PlatformBrowserLoginStatus.UNKNOWN) {
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.UNKNOWN, null, null,
                        qrScreenshot());
            }
            if (probe.status() != PlatformBrowserLoginStatus.LOGGED_IN) return pendingSnapshot();
            String storageState = activeSession.exportStorageState();
            closeActiveLogin();
            return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.LOGGED_IN, probe.accountName(), storageState);
        });
    }

    /**
     * 恢复数据库会话并访问目标平台账号接口，网络异常不判为明确失效。
     *
     * @param platform 会话所属平台
     * @param storageState Playwright 会话 JSON
     * @return 账号核验结论
     */
    public AccountProbe verifySavedLogin(PublicationPlatformType platform, String storageState) {
        requireSite(platform);
        return onBrowserThread(() -> {
            try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                    new Browser.NewContextOptions().setStorageState(storageState))) {
                return probeAccount(platform, session.context());
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "登录态检测失败", exception);
            }
        });
    }

    /**
     * 关闭当前平台登录页，不删除数据库中已有的会话。
     *
     * @param platform 当前登录平台
     */
    public void cancelLogin(PublicationPlatformType platform) {
        requireSite(platform);
        onBrowserThread(() -> {
            if (activePlatform == platform) closeActiveLogin();
            return null;
        });
    }

    /** 应用退出时关闭当前浏览器任务。 */
    @PreDestroy
    public void shutdown() {
        taskRunner.shutdown(this::closeActiveLogin);
    }

    /**
     * 对平台账号响应做分站点判定；未取到账号名时不保存会话。
     *
     * @param platform 目标平台
     * @param context 当前浏览器上下文
     * @return 明确登录、明确未登录或未知
     */
    AccountProbe probeAccount(PublicationPlatformType platform, BrowserContext context) {
        try {
            String accountUrl = requireSite(platform).accountUrl();
            RequestOptions requestOptions = RequestOptions.create().setTimeout(10_000);
            if (platform == PublicationPlatformType.SOHU) {
                accountUrl += "?_=" + System.currentTimeMillis();
                requestOptions.setHeader("Referer", "https://mp.sohu.com/");
            }
            APIResponse response = context.request().get(accountUrl, requestOptions);
            if (platform == PublicationPlatformType.YIDIAN && response.status() == 204) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if (platform == PublicationPlatformType.ZHIHU && response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            if (response.status() == 401 || response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if (!response.ok()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            if (platform == PublicationPlatformType.CNBLOGS
                    || platform == PublicationPlatformType.SEGMENTFAULT) {
                String html = new String(response.body(), StandardCharsets.UTF_8);
                return platform == PublicationPlatformType.CNBLOGS
                        ? parseCnblogsAccount(html) : parseSegmentfaultAccount(html);
            }
            if (platform == PublicationPlatformType.IMOOC) {
                return parseImoocAccount(new String(response.body(), StandardCharsets.UTF_8));
            }
            if (platform == PublicationPlatformType.WOSHIPM) {
                return probeWoshipmAccount(context, response);
            }
            if (platform == PublicationPlatformType.YUQUE) {
                return parseYuqueAccount(new String(response.body(), StandardCharsets.UTF_8));
            }
            JsonNode accountResponse = objectMapper.readTree(response.body());
            return switch (platform) {
                case ZHIHU -> parseZhihuAccount(accountResponse);
                case BAIJIAHAO -> parseBaijiahaoAccount(accountResponse);
                case OSCHINA -> parseOschinaAccount(accountResponse);
                case NETEASE -> parseNeteaseAccount(accountResponse);
                case SOHU -> parseSohuAccount(accountResponse);
                case YIDIAN -> parseYidianAccount(accountResponse);
                default -> parseJianshuAccount(accountResponse);
            };
        } catch (IOException | PlaywrightException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /** 一点号统计接口只有在业务成功且返回媒体标识时才证明登录。 */
    AccountProbe parseYidianAccount(JsonNode response) {
        if (response.path("code").asInt(-1) != 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        JsonNode media = response.path("result").path("media");
        String mediaId = media.path("id").asText("");
        String name = media.path("media_name").asText("");
        if (mediaId.isBlank() || name.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, name);
    }

    /**
     * 简书受保护的账号接口返回昵称时才确认登录；未登录请求会得到 401。
     *
     * @param response 简书账号 JSON
     * @return 登录结论
     */
    AccountProbe parseJianshuAccount(JsonNode response) {
        JsonNode account = response.path("data");
        String accountName = account.path("nickname").asText("");
        if (accountName.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, accountName);
    }

    /** 知乎账号接口必须返回账号 ID 和名称，避免仅凭 Cookie 判断成功。 */
    AccountProbe parseZhihuAccount(JsonNode response) {
        if (!response.path("id").isTextual() || !response.path("name").isTextual()
                || response.path("id").asText().isBlank() || response.path("name").asText().isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, response.path("name").asText());
    }

    /**
     * 博客园当前用户片段只含个人账号链接，不能借公开文章作者判断登录。
     *
     * @param html 当前用户区 HTML
     * @return 登录结论
     */
    AccountProbe parseCnblogsAccount(String html) {
        if (html.contains("onclick=\"return login();\"")) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        Matcher account = CNBLOGS_ACCOUNT.matcher(html);
        return account.find()
                ? new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, account.group(1))
                : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
    }

    /**
     * 百家号业务错误码优先于数据体；只有成功响应同时带账号 ID 才能保存会话。
     *
     * @param response 百家号账号 JSON
     * @return 登录结论
     */
    AccountProbe parseBaijiahaoAccount(JsonNode response) {
        if (response.path("errno").asInt(-1) == 10001401) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (!"success".equals(response.path("errmsg").asText())) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        JsonNode account = response.path("data").path("user");
        String accountId = account.path("userid").asText("");
        String accountName = account.path("name").asText("");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 开源中国用 success 和 result.userId 共同证明当前账号有效。
     *
     * @param response 开源中国账号 JSON
     * @return 登录结论
     */
    AccountProbe parseOschinaAccount(JsonNode response) {
        if (response.path("code").asInt(-1) == 40001) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (!response.path("success").asBoolean(false)) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        JsonNode account = response.path("result");
        String accountId = account.path("userId").asText("");
        String accountName = account.path("userVo").path("name").asText("");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 思否个人设置页只有登录后才包含当前账号链接，登录页没有该链接。
     *
     * @param html 个人设置页或被重定向的登录页
     * @return 登录结论
     */
    AccountProbe parseSegmentfaultAccount(String html) {
        Matcher account = SEGMENTFAULT_ACCOUNT.matcher(html);
        if (account.find()) return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, account.group(1));
        return new AccountProbe(html.contains("注册登录")
                ? PlatformBrowserLoginStatus.NOT_LOGGED_IN : PlatformBrowserLoginStatus.UNKNOWN, null);
    }

    /**
     * 慕课网账号接口返回 JSONP，只有 result=0 且含 uid 才确认登录。
     *
     * @param jsonp 慕课网账号响应
     * @return 登录结论
     */
    AccountProbe parseImoocAccount(String jsonp) {
        jsonp = jsonp.trim();
        if (!jsonp.startsWith("jsonpcallback(") || !jsonp.endsWith(")")) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        try {
            JsonNode response = objectMapper.readTree(jsonp.substring("jsonpcallback(".length(), jsonp.length() - 1));
            if (response.path("result").asInt(-1) == -11) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if (response.path("result").asInt(-1) != 0) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            String accountId = response.path("data").path("uid").asText("");
            String accountName = response.path("data").path("nickname").asText("");
            return accountId.isBlank()
                    ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                    : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                            accountName.isBlank() ? accountId : accountName);
        } catch (IOException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 网易号只有 code=1 且返回账号 tid 时才允许保存浏览器会话。
     *
     * @param response 网易号账号 JSON
     * @return 登录结论
     */
    AccountProbe parseNeteaseAccount(JsonNode response) {
        if (response.path("code").asInt(-1) == 100021) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (response.path("code").asInt(-1) != 1) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountId = response.path("data").path("tid").asText("");
        String accountName = response.path("data").path("tname").asText("");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /** 搜狐号账号列表业务码为成功且包含子账号 ID 时才确认登录。 */
    AccountProbe parseSohuAccount(JsonNode response) {
        if (response.path("code").asInt(-1) == 1211) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (response.path("code").asInt(-1) != 2_000_000) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        JsonNode accountGroups = response.path("data").path("data");
        if (!accountGroups.isArray() || accountGroups.isEmpty()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        JsonNode account = accountGroups.get(0).path("accounts").path(0);
        String accountId = account.path("id").asText("");
        String accountName = account.path("nickName").asText("");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /** 语雀服务端页面数据必须含当前用户 ID，匿名页的默认头像不能证明已登录。 */
    AccountProbe parseYuqueAccount(String html) {
        Matcher data = YUQUE_APP_DATA.matcher(html);
        if (!data.find()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        try {
            JsonNode me = objectMapper.readTree(URLDecoder.decode(data.group(1), StandardCharsets.UTF_8)).path("me");
            String accountId = me.path("id").asText("");
            String accountName = me.path("name").asText("");
            if (!accountId.isBlank()) {
                return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
            }
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        } catch (IOException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 人人都是产品经理先确认进入写作页，再用账号 ID 请求个人资料；主页内容不算登录。
     *
     * @param context 当前浏览器上下文
     * @param response 写作页响应
     * @return 登录结论
     */
    private AccountProbe probeWoshipmAccount(BrowserContext context, APIResponse response) throws IOException {
        if (!response.url().contains("/writing")) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        Matcher account = WOSHIPM_ACCOUNT.matcher(new String(response.body(), StandardCharsets.UTF_8));
        if (!account.find()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        APIResponse profile = context.request().get("https://www.woshipm.com/api2/user/profile?uid=" + account.group(1),
                RequestOptions.create().setTimeout(10_000).setHeader("X-Requested-With", "XMLHttpRequest"));
        if (!profile.ok()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        JsonNode profileResponse = objectMapper.readTree(profile.body());
        if (profileResponse.path("CODE").asInt(-1) != 200) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        JsonNode user = profileResponse.path("RESULT").path("userInfoVo");
        String accountId = user.path("uid").asText("");
        String accountName = user.path("nickName").asText("");
        return accountId.isBlank() || !accountId.equals(account.group(1))
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /** 平台表单未勾选协议时登录按钮不可用，仅在用户明确同意后勾选。 */
    private void acceptPlatformAgreement(PublicationPlatformType platform) {
        if (platform == PublicationPlatformType.SOHU) {
            var agreement = activePage.locator("em[data-role='radio-protocol']");
            if (!agreement.getAttribute("class").contains("radio-icon-sel")) agreement.click();
            return;
        }
        if (platform == PublicationPlatformType.NETEASE) {
            var agreement = activePage.frameLocator("iframe[id^='x-URS-iframe']")
                    .getByRole(AriaRole.CHECKBOX);
            if (!agreement.isChecked()) agreement.check();
            return;
        }
        var agreement = activePage.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName(platform == PublicationPlatformType.BAIJIAHAO
                        ? "请阅读并同意" : "我已阅读并同意 《服务条例》 和 《隐私声明》"));
        if (!agreement.isChecked()) agreement.check();
    }

    /**
     * 平台条款必须由用户在本次窗口明确勾选，不能由后台默认代为同意。
     *
     * @param agreedToPlatformTerms 本次窗口的用户选择
     */
    private void requirePlatformAgreement(boolean agreedToPlatformTerms, PublicationPlatformType platform) {
        if (!agreedToPlatformTerms) {
            throw new PlatformBrowserLoginException("请先阅读并同意" + platform.getDisplayName() + "用户协议");
        }
    }

    /** 当前表单仍未得到账号确认，不向页面暴露 Cookie。 */
    private BrowserLoginSnapshot pendingSnapshot() {
        return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.LOGIN_PENDING, null, null, qrScreenshot());
    }

    /** 百家号扫码期间只截取登录二维码，不展示页面上的 App 下载码。 */
    private String qrScreenshot() {
        if (!activeQrLogin) return null;
        byte[] screenshot = activePage.locator("img.tang-pass-qrcode-img").screenshot();
        return Base64.getEncoder().encodeToString(screenshot);
    }

    /** 按浏览器上下文、进程、驱动顺序关闭当前任务。 */
    private void closeActiveLogin() {
        if (activeSession != null) activeSession.close();
        activeContext = null;
        activeSession = null;
        activePage = null;
        activePlatform = null;
        startedAt = null;
        activeQrLogin = false;
    }

    /**
     * 限制只访问已定义登录页和账号接口的平台。
     *
     * @param platform 目标平台
     * @return 站点定义
     */
    private FormLoginSite requireSite(PublicationPlatformType platform) {
        FormLoginSite site = LOGIN_SITES.get(platform);
        if (site == null) throw new PlatformBrowserLoginException("该平台尚未接入表单登录");
        return site;
    }

    /**
     * 短信入口仅对已确认存在短信表单的平台开放。
     *
     * @param platform 目标平台
     */
    private void requireSmsSite(PublicationPlatformType platform) {
        if (!supportsSmsLogin(platform)) {
            throw new PlatformBrowserLoginException("该平台尚未接入短信登录");
        }
    }

    /**
     * 思否当前密码表单未能在浏览器中稳定切换，不向用户展示不可用入口。
     *
     * @param platform 目标平台
     */
    private void requirePasswordSite(PublicationPlatformType platform) {
        if (!supportsPasswordLogin(platform)) {
            throw new PlatformBrowserLoginException("该平台尚未接入密码登录");
        }
    }

    /**
     * 表单操作必须落到当前平台的同一次浏览器会话。
     *
     * @param platform 目标平台
     */
    private void requireActivePage(PublicationPlatformType platform) {
        if (activePage == null || activePlatform != platform) {
            throw new PlatformBrowserLoginException("登录窗口已关闭，请重新打开平台登录页");
        }
    }

    /**
     * Playwright 对象只在同一个工作线程使用，避免前端并发操作交叉写会话。
     *
     * @param action 本次浏览器操作
     * @return 操作结果
     */
    private <T> T onBrowserThread(Callable<T> action) {
        return taskRunner.execute(action, LOGIN_FAILURE);
    }

    /** 只接受代码中已核对过的登录地址与账号接口。 */
    private record FormLoginSite(String loginUrl, String accountUrl, boolean smsLogin, boolean passwordLogin) { }

    /** 内部进度；会话只供服务层保存，不返回浏览器页面。 */
    public record BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName,
                                       String storageState, String screenshotBase64) {
        /** 非扫码表单不返回图片，沿用已有三参数构造方式。 */
        public BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName, String storageState) {
            this(status, accountName, storageState, null);
        }
    }

    /** 站点账号核验得到的结论。 */
    public record AccountProbe(PlatformBrowserLoginStatus status, String accountName) { }
}
