package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.BrowserTaskRunner;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformLoginInteractionStage;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 第三方授权登录（微信、QQ 等）
 * 管理平台的微信、QQ 授权浏览器。打开授权页和读取扫码进度是核心入口；
 * 登录结论复用目标平台账号核验器，不以第三方授权页或 Cookie 存在判断成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformThirdPartyLoginClient {

    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    private static final Map<LoginKey, LoginEntry> LOGIN_ENTRIES = Map.ofEntries(
            entry(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.WECHAT,
                    "button.Login-socialButton:has(svg.ZDI--Wechat24)"),
            entry(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.QQ,
                    "button.Login-socialButton:has(svg.ZDI--Qq24)"),
            entry(PublicationPlatformType.JIANSHU, PlatformScanLoginMethod.WECHAT, "#weixin"),
            entry(PublicationPlatformType.JIANSHU, PlatformScanLoginMethod.QQ, "#qq"),
            entry(PublicationPlatformType.CNBLOGS, PlatformScanLoginMethod.WECHAT,
                    "img[src*='oauth/WeChat.png']"),
            entry(PublicationPlatformType.CNBLOGS, PlatformScanLoginMethod.QQ, "img[src*='oauth/QQ.png']"),
            entry(PublicationPlatformType.OSCHINA, PlatformScanLoginMethod.WECHAT, "div.social-btn", 0),
            entry(PublicationPlatformType.OSCHINA, PlatformScanLoginMethod.QQ, "div.social-btn", 1),
            entry(PublicationPlatformType.IMOOC, PlatformScanLoginMethod.WECHAT, "a.pop-sns-weixin"),
            entry(PublicationPlatformType.IMOOC, PlatformScanLoginMethod.QQ, "a.pop-sns-qq"),
            entry(PublicationPlatformType.JUEJIN, PlatformScanLoginMethod.WECHAT,
                    "img.oauth-btn[title='微信']"),
            entry(PublicationPlatformType.SOHU, PlatformScanLoginMethod.WECHAT,
                    "a[data-login='weChat']"),
            entry(PublicationPlatformType.SOHU, PlatformScanLoginMethod.QQ,
                    "a[data-login='qq']"),
            entry(PublicationPlatformType.YUQUE, PlatformScanLoginMethod.WECHAT,
                    "a.third-login-a-wechat"),
            entry(PublicationPlatformType.EASTMONEY, PlatformScanLoginMethod.WECHAT, "#btn_wx"),
            entry(PublicationPlatformType.EASTMONEY, PlatformScanLoginMethod.QQ, "#btn_qq"),
            entry(PublicationPlatformType.YIDIAN, PlatformScanLoginMethod.WECHAT, "a[href*='open.weixin.qq.com/connect/qrconnect']"),
            entry(PublicationPlatformType.YIDIAN, PlatformScanLoginMethod.QQ, "a[href*='graph.qq.com/oauth2.0/authorize']"),
            entry(PublicationPlatformType.WEIBO, PlatformScanLoginMethod.WECHAT, "text=微信登录"),
            entry(PublicationPlatformType.BILIBILI, PlatformScanLoginMethod.WECHAT, "span.btn.wechat"),
            entry(PublicationPlatformType.BILIBILI, PlatformScanLoginMethod.QQ, "span.btn.qq"),
            entry(PublicationPlatformType.TOUTIAO, PlatformScanLoginMethod.WECHAT,
                    "li[aria-label='微信登录']"),
            entry(PublicationPlatformType.TOUTIAO, PlatformScanLoginMethod.QQ, "li[aria-label='QQ登录']"),
            entry(PublicationPlatformType.CSDN, PlatformScanLoginMethod.QQ, ".login-third-qq"),
            entry(PublicationPlatformType.SMZDM, PlatformScanLoginMethod.QQ,
                    ".login-container__social-item:has-text('QQ 登录')"));

    private final FormPlatformPlaywrightLoginClient formClient;
    private final QrPlatformPlaywrightLoginClient qrClient;
    private final PlaywrightBrowserComponent browserComponent;
    private final BrowserTaskRunner taskRunner = new BrowserTaskRunner("third-party-login-browser");

    /** 浏览器线程异常统一映射为第三方登录异常。 */
    private static final Function<Throwable, RuntimeException> LOGIN_FAILURE = cause -> {
        if (cause instanceof InterruptedException) {
            return new PlatformBrowserLoginException("平台第三方登录被中断", cause);
        }
        if (cause instanceof TimeoutException) {
            return new PlatformBrowserLoginException("平台第三方登录操作超时", cause);
        }
        return new PlatformBrowserLoginException("平台第三方登录操作失败", cause);
    };
    private PlaywrightBrowserComponent.BrowserSession activeSession;
    private BrowserContext activeContext;
    private Page loginPage;
    private Page authorizationPage;
    private volatile PublicationPlatformType activePlatform;
    private Instant startedAt;

    /**
     * 只展示已核对入口的第三方扫码方式，未实现的平台不向页面暴露按钮。
     *
     * @param platform 目标发布平台
     * @return 可用的微信或 QQ 授权方式
     */
    public static List<PlatformScanLoginMethod> listScanMethods(PublicationPlatformType platform) {
        return List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ).stream()
                .filter(method -> LOGIN_ENTRIES.containsKey(new LoginKey(platform, method))).toList();
    }

    /**
     * 判断当前平台是否正使用微信或 QQ 授权，以便服务层选择正确的进度通道。
     *
     * @param platform 目标发布平台
     * @return 是否存在该平台的第三方登录任务
     */
    public boolean isActiveFor(PublicationPlatformType platform) {
        return activePlatform == platform;
    }

    /**
     * 打开目标平台的第三方授权页；平台协议需要用户先在页面明确同意。
     *
     * @param platform 登录后需取得账号的平台
     * @param method 微信或 QQ 授权
     * @param agreedToPlatformTerms 用户是否同意平台协议
     * @return 授权页扫码图片和等待状态
     */
    public BrowserLoginSnapshot startLogin(PublicationPlatformType platform, PlatformScanLoginMethod method,
                                           boolean agreedToPlatformTerms) {
        LoginEntry entry = requireEntry(platform, method);
        return onBrowserThread(() -> {
            closeActiveLogin();
            try {
                // 1. 创建独立上下文，第三方授权结果只能写入本次目标平台会话。
                activeSession = browserComponent.openBrowser(
                        new Browser.NewContextOptions().setViewportSize(900, 720));
                activeContext = activeSession.context();
                loginPage = activeSession.newPage();
                loginPage.setDefaultTimeout(15_000);
                String loginUrl = FormPlatformPlaywrightLoginClient.supportsPlatform(platform)
                        ? FormPlatformPlaywrightLoginClient.loginUrl(platform)
                        : QrPlatformPlaywrightLoginClient.loginUrl(platform);
                loginPage.navigate(loginUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
                if (platform == PublicationPlatformType.YIDIAN) {
                    // 一点号先打开登录弹窗，微信与 QQ 入口才会出现在页面中。
                    loginPage.getByText("登录", new Page.GetByTextOptions().setExact(true)).click();
                }
                // 2. 用户已同意协议时才勾选平台登录页协议，再打开微信或 QQ 授权。
                if (platform == PublicationPlatformType.OSCHINA) acceptOschinaAgreement(agreedToPlatformTerms);
                if (platform == PublicationPlatformType.TOUTIAO) acceptToutiaoAgreement(agreedToPlatformTerms);
                if (platform == PublicationPlatformType.SOHU) acceptSohuAgreement(agreedToPlatformTerms);
                if (platform == PublicationPlatformType.YUQUE) acceptYuqueAgreement(agreedToPlatformTerms);
                if (platform == PublicationPlatformType.EASTMONEY) acceptEastmoneyAgreement(agreedToPlatformTerms);
                List<Page> previousPages = List.copyOf(activeContext.pages());
                String pageUrlBeforeAuthorization = loginPage.url();
                if (platform == PublicationPlatformType.EASTMONEY) {
                    // 创作平台登录控件位于跨域 iframe，仍沿用同一目标账号核验流程。
                    loginPage.frameLocator("iframe[src*='exaccount2.eastmoney.com/Home/Login4']")
                            .locator(entry.selector()).click();
                } else {
                    loginPage.locator(entry.selector()).nth(entry.index()).click();
                }
                authorizationPage = waitForAuthorizationPage(previousPages, pageUrlBeforeAuthorization);
                if (authorizationPage == null) {
                    throw new PlatformBrowserLoginException(platform.getDisplayName()
                            + "第三方授权页未打开，请改用其他登录方式或稍后重试");
                }
                // 3. 只保留实际打开的授权页供页面扫码和后续目标平台核验。
                activePlatform = platform;
                startedAt = Instant.now();
                scheduleExpiry(startedAt);
                log.info("平台第三方授权页已打开，platformType={} scanMethod={}", platform, method);
                return snapshot(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            } catch (PlatformBrowserLoginException exception) {
                closeActiveLogin();
                throw exception;
            } catch (PlaywrightException exception) {
                closeActiveLogin();
                throw new PlatformBrowserLoginException(platform.getDisplayName() + "第三方登录页打开失败", exception);
            }
        });
    }

    /**
     * 查询授权进度；只有目标平台账号接口确认账号后才返回可落库会话。
     *
     * @param platform 当前登录任务的平台
     * @return 授权进度和可选会话
     */
    public BrowserLoginSnapshot readLoginProgress(PublicationPlatformType platform) {
        return onBrowserThread(() -> {
            // 1. 先核对任务归属与时效，不从其他平台浏览器读取授权结果。
            if (activeContext == null || activePlatform != platform) {
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.NOT_STARTED, null, null, null);
            }
            if (startedAt.plus(LOGIN_LIFETIME).isBefore(Instant.now())) {
                closeActiveLogin();
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.EXPIRED, null, null, null);
            }
            // 2. 第三方二维码仍在等待用户操作时，不反复请求目标站账号接口。
            if (awaitingThirdPartyAuthorization()) {
                return snapshot(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            }
            // 3. 授权页只证明第三方身份；保存会话必须以目标平台账号接口为准。
            AccountProbe probe = probeTargetAccount(platform);
            if (probe.status() == PlatformBrowserLoginStatus.LOGGED_IN) {
                // 4. 目标账号明确登录后才导出会话，供服务层保存到该平台记录。
                String storageState = activeSession.exportStorageState();
                closeActiveLogin();
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.LOGGED_IN,
                        probe.accountName(), null, storageState);
            }
            return snapshot(probe.status() == PlatformBrowserLoginStatus.UNKNOWN
                    ? PlatformBrowserLoginTaskStatus.UNKNOWN : PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
        });
    }

    /**
     * 取消指定平台的本次授权，不删除数据库中已经保存的登录态。
     *
     * @param platform 目标平台
     */
    public void cancelLogin(PublicationPlatformType platform) {
        onBrowserThread(() -> {
            if (activePlatform == platform) closeActiveLogin();
            return null;
        });
    }

    /** 进程退出时关闭尚未完成的第三方授权浏览器。 */
    @PreDestroy
    public void shutdown() {
        taskRunner.shutdown(this::closeActiveLogin);
    }

    /**
     * 通过已有平台核验器判断账号，避免另造一套宽松的 Cookie 判定。
     *
     * @param platform 第三方授权的目标平台
     * @return 目标平台账号核验结果
     */
    private AccountProbe probeTargetAccount(PublicationPlatformType platform) {
        // 表单平台与原生扫码平台各有账号接口；第三方授权只复用其成功判定。
        if (FormPlatformPlaywrightLoginClient.supportsPlatform(platform)) {
            FormPlatformPlaywrightLoginClient.AccountProbe probe = formClient.probeAccount(platform, activeContext);
            return new AccountProbe(probe.status(), probe.accountName());
        }
        QrPlatformPlaywrightLoginClient.AccountProbe probe = qrClient.probeAccount(platform, activeContext);
        return new AccountProbe(probe.status(), probe.accountName());
    }

    /**
     * 等待第三方授权页真正打开；按钮无响应时不能把原登录页当成扫码页。
     *
     * @param previousPages 点击前已存在的页面
     * @param previousUrl 点击前登录页地址
     * @return 新授权页；按钮无响应时返回 null
     */
    private Page waitForAuthorizationPage(List<Page> previousPages, String previousUrl) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Page popup = activeContext.pages().stream()
                    .filter(page -> !previousPages.contains(page)).findFirst().orElse(null);
            if (popup != null) {
                if (!"about:blank".equals(popup.url())) return popup;
            }
            if (!previousUrl.equals(loginPage.url())) return loginPage;
            loginPage.waitForTimeout(250);
        }
        return null;
    }

    /**
     * 判断二维码授权页是否仍未回到目标平台，避免轮询阻塞在账号接口。
     *
     * @return 是否仍在微信、QQ 或博客园微信授权页面
     */
    private boolean awaitingThirdPartyAuthorization() {
        if (authorizationPage == null || authorizationPage.isClosed()) return false;
        String url = authorizationPage.url();
        return url.contains("open.weixin.qq.com/connect/qrconnect")
                || url.contains("graph.qq.com/oauth2.0/show")
                || url.contains("account.cnblogs.com/signin/wechat-mp");
    }

    /**
     * 截取第三方扫码区域；QQ 授权码位于登录 iframe 中。
     *
     * @param status 当前第三方授权登录任务进度
     * @return 供页面展示的扫码图片和状态
     */
    private BrowserLoginSnapshot snapshot(PlatformBrowserLoginTaskStatus status) {
        byte[] screenshot;
        try {
            // 授权页所属站点决定二维码位置；授权结束后才回退到整页提示。
            if (authorizationPage == null || authorizationPage.isClosed()) {
                screenshot = loginPage.screenshot();
            } else if (authorizationPage.url().contains("open.weixin.qq.com")) {
                // 微信授权页可能先展示快捷账号，再出现可供异地手机扫描的二维码。
                authorizationPage.waitForFunction("() => [...document.querySelectorAll('button')]"
                        + ".some(button => button.offsetWidth > 0 && button.textContent.includes('使用其他头像、昵称或账号'))"
                        + " || [...document.querySelectorAll('img.js_qrcode_img, img.web_qrcode_img')]"
                        + ".some(image => image.offsetWidth > 0 && image.complete && image.naturalWidth > 0)",
                        null, new Page.WaitForFunctionOptions().setTimeout(10_000));
                var otherAccount = authorizationPage.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("使用其他头像、昵称或账号"));
                if (otherAccount.isVisible()) otherAccount.click();
                authorizationPage.waitForFunction("() => [...document.querySelectorAll('img.js_qrcode_img, img.web_qrcode_img')]"
                        + ".some(image => image.offsetWidth > 0 && image.complete && image.naturalWidth > 0)",
                        null, new Page.WaitForFunctionOptions().setTimeout(10_000));
                screenshot = authorizationPage.locator("img.js_qrcode_img, img.web_qrcode_img:visible")
                        .first().screenshot();
            } else if (authorizationPage.url().contains("graph.qq.com")) {
                screenshot = authorizationPage.frameLocator("#ptlogin_iframe")
                        .locator("#qrlogin_img").screenshot();
            } else if (authorizationPage.url().contains("account.cnblogs.com/signin/wechat-mp")) {
                screenshot = authorizationPage.locator("img[alt='微信二维码']").screenshot();
            } else {
                screenshot = authorizationPage.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            }
        } catch (PlaywrightException exception) {
            screenshot = loginPage.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        }
        PlatformLoginInteractionStage stage = authorizationPage == null || authorizationPage.isClosed()
                ? PlatformLoginInteractionStage.INTERACTION_WINDOW_CLOSED
                : awaitingThirdPartyAuthorization()
                ? PlatformLoginInteractionStage.WAITING_FOR_THIRD_PARTY
                : PlatformLoginInteractionStage.RETURNED_TO_PLATFORM;
        return new BrowserLoginSnapshot(status, null, Base64.getEncoder().encodeToString(screenshot), null, stage);
    }

    /**
     * 开源中国授权入口要求先同意本站协议；不能由后台默认为同意。
     *
     * @param agreedToPlatformTerms 用户在页面是否已明确同意
     */
    private void acceptOschinaAgreement(boolean agreedToPlatformTerms) {
        if (!agreedToPlatformTerms) throw new PlatformBrowserLoginException("请先阅读并同意开源中国用户协议");
        var agreement = loginPage.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName("我已阅读并同意 《服务条例》 和 《隐私声明》"));
        if (!agreement.isChecked()) agreement.check();
    }

    /**
     * 头条号的微信、QQ 登录按钮均要求先勾选本站协议。
     *
     * @param agreedToPlatformTerms 用户在页面是否已明确同意
     */
    private void acceptToutiaoAgreement(boolean agreedToPlatformTerms) {
        if (!agreedToPlatformTerms) throw new PlatformBrowserLoginException("请先阅读并同意头条号用户协议");
        var agreement = loginPage.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName("协议勾选框"));
        if (!agreement.isChecked()) agreement.check();
    }

    /**
     * 搜狐号第三方授权前沿用账号登录弹窗的协议确认，不代替用户同意。
     *
     * @param agreedToPlatformTerms 用户在管理页是否已勾选协议
     */
    private void acceptSohuAgreement(boolean agreedToPlatformTerms) {
        if (!agreedToPlatformTerms) throw new PlatformBrowserLoginException("请先阅读并同意搜狐号用户协议");
        loginPage.getByText("登录/注册", new Page.GetByTextOptions().setExact(true)).click();
        var agreement = loginPage.locator("em[data-role='radio-protocol']");
        if (!agreement.getAttribute("class").contains("radio-icon-sel")) agreement.click();
    }

    /** 语雀微信授权前须由用户明确同意本站协议。 */
    private void acceptYuqueAgreement(boolean agreedToPlatformTerms) {
        if (!agreedToPlatformTerms) throw new PlatformBrowserLoginException("请先阅读并同意语雀用户协议");
        var agreement = loginPage.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName("我已阅读并同意语雀 服务协议 和 隐私权政策"));
        if (!agreement.isChecked()) agreement.check();
    }

    /** 东方财富授权按钮由登录 iframe 的协议勾选控制，用户未同意时不代为授权。 */
    private void acceptEastmoneyAgreement(boolean agreedToPlatformTerms) {
        if (!agreedToPlatformTerms) throw new PlatformBrowserLoginException("请先阅读并同意东方财富用户协议");
        var agreement = loginPage.frameLocator("iframe[src*='exaccount2.eastmoney.com/Home/Login4']")
                .locator("#mobile_login_content img.selectbox.unselected");
        if (agreement.isVisible()) agreement.click();
    }

    /**
     * 旧任务到期时只关闭相同开始时间的浏览器，避免影响新扫码。
     *
     * @param loginStartedAt 当前任务的开始时间
     */
    private void scheduleExpiry(Instant loginStartedAt) {
        taskRunner.schedule(() -> {
            if (loginStartedAt.equals(startedAt)) closeActiveLogin();
        }, LOGIN_LIFETIME.toSeconds(), TimeUnit.SECONDS);
    }

    /** 按上下文、浏览器、驱动顺序释放本次授权页面。 */
    private void closeActiveLogin() {
        if (activeSession != null) activeSession.close();
        activeSession = null;
        activeContext = null;
        loginPage = null;
        authorizationPage = null;
        activePlatform = null;
        startedAt = null;
    }

    /**
     * 仅允许打开平台已核对的授权按钮，拒绝任意外部 URL。
     *
     * @param platform 目标平台
     * @param method 扫码来源
     * @return 已核对的按钮定位
     */
    private LoginEntry requireEntry(PublicationPlatformType platform, PlatformScanLoginMethod method) {
        LoginEntry entry = LOGIN_ENTRIES.get(new LoginKey(platform, method));
        if (entry == null) throw new PlatformBrowserLoginException("该平台未接入此扫码方式");
        return entry;
    }

    /**
     * 浏览器对象只能在工作线程中使用，避免并发操作同一授权会话。
     *
     * @param action 要执行的浏览器动作
     * @return 浏览器动作结果
     */
    private <T> T onBrowserThread(Callable<T> action) {
        return taskRunner.execute(action, LOGIN_FAILURE);
    }

    private static Map.Entry<LoginKey, LoginEntry> entry(PublicationPlatformType platform,
                                                         PlatformScanLoginMethod method, String selector) {
        return entry(platform, method, selector, 0);
    }

    private static Map.Entry<LoginKey, LoginEntry> entry(PublicationPlatformType platform,
                                                         PlatformScanLoginMethod method, String selector, int index) {
        return Map.entry(new LoginKey(platform, method), new LoginEntry(selector, index));
    }

    private record LoginKey(PublicationPlatformType platform, PlatformScanLoginMethod method) { }

    private record LoginEntry(String selector, int index) { }

    private record AccountProbe(PlatformBrowserLoginStatus status, String accountName) { }

    /** 内部授权结果；storageState 只交给服务层落库，不返回页面。 */
    public record BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName,
                                       String screenshotBase64, String storageState,
                                       PlatformLoginInteractionStage interactionStage) {
        /** 无第三方阶段的结果保留原有构造方式。 */
        public BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName,
                                    String screenshotBase64, String storageState) {
            this(status, accountName, screenshotBase64, storageState, null);
        }
    }
}
