package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.BrowserTaskRunner;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.fastjson.JSONException;
import jakarta.annotation.Resource;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
public class PlatformAccountLoginClient {

    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    /** 搜狐焦点昵称可能以“反斜杠 u + 四位十六进制”的转义文本返回，需还原成中文。 */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Map<PublicationPlatformType, FormLoginSite> LOGIN_SITES = Map.ofEntries(
            Map.entry(PublicationPlatformType.ZHIHU, new FormLoginSite(
                    "https://www.zhihu.com/signin", "https://www.zhihu.com/api/v4/me", true, true)),
            Map.entry(PublicationPlatformType.JIANSHU, new FormLoginSite(
                    "https://www.jianshu.com/sign_in", "https://www.jianshu.com/settings/basic.json", false, true)),
            Map.entry(PublicationPlatformType.BAIJIAHAO, new FormLoginSite(
                    "https://baijiahao.baidu.com/builder/theme/bjh/login",
                    "https://baijiahao.baidu.com/builder/app/appinfo", true, true)),
            Map.entry(PublicationPlatformType.IMOOC, new FormLoginSite(
                    "https://www.imooc.com/user/newlogin",
                    "https://www.imooc.com/u/card", true, true)),
            Map.entry(PublicationPlatformType.NETEASE, new FormLoginSite(
                    "https://mp.163.com/login.html",
                    "https://mp.163.com/wemedia/navinfo.do", false, true)),
            Map.entry(PublicationPlatformType.SOHU, new FormLoginSite(
                    "https://mp.sohu.com/", "https://mp.sohu.com/mpbp/bp/account/list", false, true)),
            // 搜狐焦点只开放微信授权登录，登录弹窗在首页，账号接口与页面自身的登录判断一致
            Map.entry(PublicationPlatformType.SOHU_FOCUS, new FormLoginSite(
                    "https://house.focus.cn/", "https://login.focus.cn/passport/getUserInfo", false, false)),
            Map.entry(PublicationPlatformType.YIDIAN, new FormLoginSite(
                    "https://mp.yidianzixun.com/", "https://mp.yidianzixun.com/model/Agreement?type=1",
                    false, false)));

    @Resource
    private PlaywrightBrowserComponent browserComponent;
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

    /**
     * 关闭慕课网登录页加载后弹出的促销广告，否则广告遮住登录框，账号、短信和第三方登录按钮都点不到。
     * <p>广告在页面加载后才出现，最多等 3 秒；没出现就直接继续，只点广告自带的关闭按钮。</p>
     *
     * @param page 慕课网登录页
     */
    static void closeImoocPromotionPopup(Page page) {
        Locator close = page.locator("#js-advpop-close");
        try {
            close.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3_000));
        } catch (TimeoutError exception) {
            return;
        }
        close.click();
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
                if (platform == PublicationPlatformType.IMOOC) closeImoocPromotionPopup(activePage);
                activePlatform = platform;
                startedAt = Instant.now();
                Instant loginStartedAt = startedAt;
                taskRunner.schedule(() -> {
                    if (loginStartedAt.equals(startedAt)) closeActiveLogin();
                }, LOGIN_LIFETIME.toSeconds(), TimeUnit.SECONDS);
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                // 关闭浏览器前截取失败现场，供用户对照页面判断原因
                PlatformBrowserLoginException failure = PlatformLoginPageFailure.toException(activePage, platform, exception);
                closeActiveLogin();
                throw failure;
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
                    // 表单里还有一个“显示密码”用的隐藏文本框，占位文字同为“密码”，只能按真实密码框定位
                    loginFrame.locator("input[type='password'][name='password']").fill(password);
                    loginFrame.getByRole(AriaRole.LINK,
                            new FrameLocator.GetByRoleOptions().setName("登录 / 注册")).click();
                    return pendingSnapshot();
                } else {
                    // 其余支持密码登录的只有搜狐号
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByText("账号登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByPlaceholder("请输入邮箱/手机号").fill(account);
                    activePage.getByPlaceholder("请输入密码").fill(password);
                    acceptPlatformAgreement(platform);
                }
                activePage.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("登录").setExact(true)).click();
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
                } else if (platform == PublicationPlatformType.BAIJIAHAO) {
                    requirePlatformAgreement(agreedToPlatformTerms, platform);
                    activePage.getByText("短信快捷登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("手机号")).fill(phone);
                    acceptPlatformAgreement(platform);
                    activePage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发送验证码")).click();
                } else {
                    activePage.getByText("验证码登录", new Page.GetByTextOptions().setExact(true)).click();
                    activePage.getByPlaceholder("短信登录仅限中国大陆用户").fill(phone);
                    activePage.getByText("获取验证码", new Page.GetByTextOptions().setExact(true)).click();
                }
                return pendingSnapshot();
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "获取验证码失败，请查看页面提示", exception);
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
                String codeName = platform == PublicationPlatformType.IMOOC ? "请输入短信验证码" : "验证码";
                if (platform == PublicationPlatformType.ZHIHU) {
                    activePage.getByPlaceholder("输入 6 位短信验证码").fill(code);
                    activePage.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("登录/注册").setExact(true)).click();
                    return pendingSnapshot();
                }
                if (platform == PublicationPlatformType.IMOOC) activePage.getByPlaceholder(codeName).fill(code);
                else activePage.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName(codeName)).fill(code);
                if (platform == PublicationPlatformType.BAIJIAHAO) acceptPlatformAgreement(platform);
                activePage.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("登录").setExact(true)).click();
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
            // 1. 按平台构造受保护的账号请求，复用当前登录上下文
            String accountUrl = requireSite(platform).accountUrl();
            RequestOptions requestOptions = RequestOptions.create().setTimeout(10_000);
            if (platform == PublicationPlatformType.SOHU) {
                accountUrl += "?_=" + System.currentTimeMillis();
                requestOptions.setHeader("Referer", "https://mp.sohu.com/");
            }
            if (platform == PublicationPlatformType.SOHU_FOCUS) {
                // 页面自己从焦点首页请求账号接口，核验时带上同样的站内来源
                requestOptions.setHeader("Referer", "https://house.focus.cn/");
            }
            APIResponse response = context.request().get(accountUrl, requestOptions);
            // 2. 先解释 HTTP 状态；知乎访问限制无法证明会话失效，其余认证拒绝才判未登录
            if (platform == PublicationPlatformType.ZHIHU && response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            if (response.status() == 401 || response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if (!response.ok()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            // 3. 按平台分别解析 HTML、JSONP 或 JSON，解析失败保持未知而非登录成功
            if (platform == PublicationPlatformType.IMOOC) {
                return parseImoocAccount(new String(response.body(), StandardCharsets.UTF_8));
            }
            JSONObject accountResponse = JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8));
            return switch (platform) {
                case ZHIHU -> parseZhihuAccount(accountResponse);
                case BAIJIAHAO -> parseBaijiahaoAccount(accountResponse);
                case NETEASE -> parseNeteaseAccount(accountResponse);
                case SOHU -> parseSohuAccount(accountResponse);
                case YIDIAN -> parseYidianAccount(accountResponse);
                case SOHU_FOCUS -> parseSohuFocusAccount(accountResponse);
                default -> parseJianshuAccount(accountResponse);
            };
        } catch (JSONException | PlaywrightException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 一点号用户协议接口按当前会话填入签署人账号 ID，以此判断是否登录。
     * <p>已登录时 result.userId 为正数账号 ID，未登录时为空字符串；统计接口不含账号身份，不能用于核验。
     * 账号名取签署人名称 userName，为空时用账号 ID；手机号不读取。</p>
     *
     * @param response 一点号用户协议接口响应
     * @return 已登录（签署人名称或账号 ID）、未登录，或结构不符时无法确认
     */
    AccountProbe parseYidianAccount(JSONObject response) {
        // 1. 未登录同样返回成功码，业务码只用于排除异常响应，不能据此判断登录状态
        if ((int) readAccountNumber(response, "$.code", -1) != 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 2. 签署人 ID 为空字符串是平台对匿名会话的明确返回
        if ("".equals(JSONPath.eval(response, "$.result.userId"))) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        // 3. 只有正数账号 ID 才确认登录；缺失或其他结构保持无法确认，名称缺失时以 ID 展示
        long accountId = readAccountNumber(response, "$.result.userId", 0);
        if (accountId <= 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(response, "$.result.userName", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? Long.toString(accountId) : accountName);
    }

    /**
     * 搜狐焦点账号接口按页面自身规则判断：errorCode 为 0 且带账号资料才算登录，401 为未登录。
     * <p>账号名取昵称 nickName，为空时取用户名 userName；两者都为空时无法确认。手机号不读取。</p>
     *
     * @param response 搜狐焦点账号接口响应
     * @return 已登录（昵称或用户名）、未登录，或结构不符时无法确认
     */
    AccountProbe parseSohuFocusAccount(JSONObject response) {
        // 1. 未登录时接口仍是 HTTP 200，只能看业务码
        long errorCode = readAccountNumber(response, "$.errorCode", -1);
        if (errorCode == 401) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (errorCode != 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 2. 昵称优先，平台可能返回转义文本，按页面自身的还原方式转成中文
        String accountName = readAccountText(response, "$.data.nickName", "");
        if (accountName.isBlank()) {
            accountName = readAccountText(response, "$.data.userName", "");
        }
        if (accountName.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        accountName = UNICODE_ESCAPE.matcher(accountName)
                .replaceAll(escape -> String.valueOf((char) Integer.parseInt(escape.group(1), 16)));
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, accountName);
    }

    /**
     * 简书受保护的账号接口返回昵称时才确认登录；未登录请求会得到 401。
     *
     * @param response 简书账号 JSON
     * @return 登录结论
     */
    AccountProbe parseJianshuAccount(JSONObject response) {
        // 受保护接口仍须返回有效昵称，容器或空值不能当成登录证据
        Object account = JSONPath.eval(response, "$.data");
        String accountName = readAccountText(account, "$.nickname", "");
        if (accountName.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, accountName);
    }

    /**
     * 知乎账号接口必须返回文本型账号 ID 和名称，避免仅凭 Cookie 判断成功。
     * @param response 平台账号响应
     * @return 账号核验结论
     */
    AccountProbe parseZhihuAccount(JSONObject response) {
        // 标识与名称都必须是非空文本，不将数字或容器强制转换为账号
        if (!(JSONPath.eval(response, "$.id") instanceof String) || !(JSONPath.eval(response, "$.name") instanceof String)
                || readAccountText(response, "$.id", "").isBlank() || readAccountText(response, "$.name", "").isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, readAccountText(response, "$.name", ""));
    }

    /**
     * 百家号业务错误码优先于数据体；只有成功响应同时带账号 ID 才能保存会话。
     *
     * @param response 百家号账号 JSON
     * @return 登录结论
     */
    AccountProbe parseBaijiahaoAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        if ((int) readAccountNumber(response, "$.errno", -1) == 10001401) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (!"success".equals(readAccountText(response, "$.errmsg", ""))) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.data.user");
        String accountId = readAccountText(account, "$.userid", "");
        String accountName = readAccountText(account, "$.name", "");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 慕课网账号接口返回 JSONP，只有 result=0 且含 uid 才确认登录。
     *
     * @param jsonp 慕课网账号响应
     * @return 登录结论
     */
    AccountProbe parseImoocAccount(String jsonp) {
        // 1. 只拆除已知 JSONP 包装，其他正文不猜测为账号响应
        jsonp = jsonp.trim();
        if (!jsonp.startsWith("jsonpcallback(") || !jsonp.endsWith(")")) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        try {
            // 2. 业务码优先，明确未登录与无法确认分别返回
            Object response = JSON.parseObject(jsonp.substring("jsonpcallback(".length(), jsonp.length() - 1));
            if ((int) readAccountNumber(response, "$.result", -1) == -11) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if ((int) readAccountNumber(response, "$.result", -1) != 0) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            // 3. 只有有效账号标识才确认身份，昵称缺失时显示该标识
            String accountId = readAccountText(response, "$.data.uid", "");
            String accountName = readAccountText(response, "$.data.nickname", "");
            return accountId.isBlank()
                    ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                    : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                            accountName.isBlank() ? accountId : accountName);
        } catch (JSONException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 网易号只有 code=1 且返回账号 tid 时才允许保存浏览器会话。
     *
     * @param response 网易号账号 JSON
     * @return 登录结论
     */
    AccountProbe parseNeteaseAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        if ((int) readAccountNumber(response, "$.code", -1) == 100021) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if ((int) readAccountNumber(response, "$.code", -1) != 1) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        String accountId = readAccountText(response, "$.data.tid", "");
        String accountName = readAccountText(response, "$.data.tname", "");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 搜狐号账号列表业务码为成功且包含子账号 ID 时才确认登录。
     * @param response 平台账号响应
     * @return 账号核验结论
     */
    AccountProbe parseSohuAccount(JSONObject response) {
        // 1. 先核对平台业务状态，明确未登录与无法确认分别处理
        if ((int) readAccountNumber(response, "$.code", -1) == 1211) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if ((int) readAccountNumber(response, "$.code", -1) != 2_000_000) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 2. 只接受非空账号组数组，缺失或其他结构不猜测为有效列表
        Object accountGroups = JSONPath.eval(response, "$.data.data");
        if (!(accountGroups instanceof com.alibaba.fastjson.JSONArray groups) || groups.isEmpty()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 3. 核验首个子账号标识，昵称缺失时以该标识展示
        Object account = JSONPath.eval(groups, "$[0].accounts[0]");
        String accountId = readAccountText(account, "$.id", "");
        String accountName = readAccountText(account, "$.nickName", "");
        return accountId.isBlank()
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
        var agreement = activePage.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions().setName("请阅读并同意"));
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
     * 密码入口仅对已确认存在密码表单的平台开放，不向用户展示不可用入口。
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

    /**
     * 提取账号接口标量文本，缺失或容器结构不能充当账号标识。
     * @param response 平台响应或账号子对象
     * @param path 字段路径
     * @param fallback 字段不可用时的返回值
     * @return 标量文本或默认值
     */
    private static String readAccountText(Object response, String path, String fallback) {
        Object value = JSONPath.eval(response, path);
        return value instanceof String || value instanceof Number || value instanceof Boolean
                ? value.toString() : fallback;
    }

    /**
     * 读取账号业务码或数字标识，非法值不能默认为成功码零。
     * @param response 平台响应或账号子对象
     * @param path 字段路径
     * @param fallback 缺失或非法值的默认值
     * @return 数字字段值或默认值
     */
    private static long readAccountNumber(Object response, String path, long fallback) {
        Object value = JSONPath.eval(response, path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 部分平台返回字符串业务码；解析失败仍保持无法确认的结论
        try {
            return value instanceof String text ? Long.parseLong(text.strip()) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

}
