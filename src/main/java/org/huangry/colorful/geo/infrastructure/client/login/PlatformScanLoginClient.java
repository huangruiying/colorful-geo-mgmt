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
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DouyinIdentityVerificationMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformLoginInteractionStage;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.springframework.stereotype.Component;

import java.net.URI;
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
 * 平台 App 扫码
 * 管理使用平台 App 扫码的独立 Playwright 登录任务。
 * 核心入口为打开二维码、读取登录进度和复核数据库会话；抖音二次验证继续复用原扫码会话，不创建文章。
 */
@Slf4j
@Component
public class PlatformScanLoginClient {

    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    private static final Pattern WECHAT_TOKEN = Pattern.compile("\\bt:\\s*['\"]([0-9]+)['\"]");
    private static final Pattern WECHAT_ACCOUNT = Pattern.compile("\\bnick_name:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern WEIBO_CONFIG = Pattern.compile("config:\\s*JSON\\.parse\\('(.+?)'\\)", Pattern.DOTALL);
    /** 51CTO 个人首页顶部的账号链接，取用户 ID 和昵称。 */
    private static final Pattern CTO51_ACCOUNT = Pattern.compile(
            "class=\"name\"[^>]*>\\s*<a href=\"/space\\?uid=(\\d+)\">([^<]*)</a>");
    private static final Pattern WOSHIPM_ACCOUNT = Pattern.compile(
            "var\\s+userSettings\\s*=\\s*\\{[^}]*\"uid\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern DOUYIN_PAGE_ACCOUNT_ID = Pattern.compile("抖音号\\s*[：:]\\s*([A-Za-z0-9._-]{3,32})");
    private static final String CSDN_ACCOUNT_URL = "https://bizapi.csdn.net/blog-console-api/v1/user/info";
    /** 51CTO 登录页取微信公众号二维码的接口，返回本次扫码标识 sid 与二维码地址。 */
    private static final String CTO51_QR_CODE_URL = "https://home.51cto.com/wechat/get-qr-code";
    /** 51CTO 登录页轮询扫码结果的接口，isok 为 1 表示已扫码确认。 */
    private static final String CTO51_SCAN_PULL_URL = "https://home.51cto.com/wechat/scan-pull?sid=";
    /** 51CTO 扫码确认后页面跳转的地址，登录 Cookie 在这一步写入。 */
    private static final String CTO51_LOGIN_URL =
            "https://home.51cto.com/user/reg-login?from=&iframe=0&is_go_to_user_set_mobile=1";
    private static final String WECHAT_SHOW_QR_CODE_URL = "https://mp.weixin.qq.com/cgi-bin/showqrcode";
    private static final QrLoginSite CSDN_WECHAT_SITE = new QrLoginSite(
            "https://passport.csdn.net/login", "img[src^='data:image/jpeg;base64,']",
            "img[src^='data:image/jpeg;base64,']", CSDN_ACCOUNT_URL,
            "span:has-text('微信登录')");
    private static final Map<PublicationPlatformType, QrLoginSite> LOGIN_SITES = Map.ofEntries(
            // 知乎的码由 canvas 绘制；外层容器只用于截图，不能用于加载完成判定。
            Map.entry(PublicationPlatformType.ZHIHU, new QrLoginSite(
                    "https://www.zhihu.com/signin", "canvas.Qrcode-qrcode", ".Qrcode-img",
                    "https://www.zhihu.com/api/v4/me")),
            Map.entry(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT, new QrLoginSite(
                    "https://mp.weixin.qq.com/", ".login__type__container__scan__qrcode",
                    ".login__type__container__scan__qrcode", "https://mp.weixin.qq.com/")),
            Map.entry(PublicationPlatformType.JUEJIN, new QrLoginSite(
                    "https://juejin.cn/login?to=https%3A%2F%2Fjuejin.cn%2Feditor%2Fdrafts%2Fnew",
                    ".qrcode-img-wrap .qrcode-img", ".qrcode-img-wrap",
                    "https://api.juejin.cn/user_api/v1/user/get?aid=2608&uuid=&not_self=0")),
            Map.entry(PublicationPlatformType.WEIBO, new QrLoginSite(
                    "https://passport.weibo.com/sso/signin?entry=miniblog&source=miniblog&disp=popup"
                            + "&url=https%3A%2F%2Fweibo.com%2F&from=weibopro",
                    "img[src^='https://v2.qr.weibo.cn/inf/gen']",
                    "img[src^='https://v2.qr.weibo.cn/inf/gen']",
                    "https://card.weibo.com/article/v5/editor")),
            Map.entry(PublicationPlatformType.BILIBILI, new QrLoginSite(
                    "https://passport.bilibili.com/login", "img[alt='Scan me!']", "img[alt='Scan me!']",
                    "https://api.bilibili.com/x/web-interface/nav?build=0&mobi_app=web")),
            Map.entry(PublicationPlatformType.TOUTIAO, new QrLoginSite(
                    "https://mp.toutiao.com/", "img[aria-label='二维码']", "img[aria-label='二维码']",
                    "https://mp.toutiao.com/mp/agw/media/get_media_info")),
            Map.entry(PublicationPlatformType.XIAOHONGSHU, new QrLoginSite(
                    "https://creator.xiaohongshu.com/login", "img[src^='data:image/png;base64,']",
                    "img[src^='data:image/png;base64,']",
                    "https://creator.xiaohongshu.com/api/galaxy/user/info",
                    "img[src^='data:image/png;base64,']")),
            Map.entry(PublicationPlatformType.DOUYIN, new QrLoginSite(
                    "https://creator.douyin.com/creator-micro/home", "img[aria-label='二维码']",
                    "img[aria-label='二维码']",
                    "https://creator.douyin.com/aweme/v1/creator/pc/user/info/")),
            Map.entry(PublicationPlatformType.CSDN, new QrLoginSite(
                    "https://passport.csdn.net/login", "img[alt='Scan me!']",
                    "img[alt='Scan me!']", CSDN_ACCOUNT_URL,
                    "span:has-text('APP登录')")),
            Map.entry(PublicationPlatformType.SMZDM, new QrLoginSite(
                    "https://zhiyou.smzdm.com/user/login/window/",
                    "canvas.login-container__qrcode-real", ".login-container__qrcode-content",
                    "https://zhiyou.smzdm.com/user/info/jsonp_get_current")),
            // 人人都是产品经理微信登录页直接展示公众号二维码，扫码后由本站轮询结果并跳回主站。
            Map.entry(PublicationPlatformType.WOSHIPM, new QrLoginSite(
                    "https://passport.woshipm.com/user/wechatServiceLogin.html?FS=PM&REDIRECT=https://www.woshipm.com/",
                    "img[src^='https://mp.weixin.qq.com/cgi-bin/showqrcode']",
                    "img[src^='https://mp.weixin.qq.com/cgi-bin/showqrcode']",
                    "https://www.woshipm.com/writing")),
            // 51CTO 静态资源和 api-media 接口会拦截自动化浏览器：二维码由取码接口获取后单独打开（见 openCto51WechatQrCode），
            // 账号用 home.51cto.com 个人首页核验
            Map.entry(PublicationPlatformType.CTO_51, new QrLoginSite(
                    "https://home.51cto.com/index",
                    "img[src^='https://mp.weixin.qq.com/cgi-bin/showqrcode']",
                    "img[src^='https://mp.weixin.qq.com/cgi-bin/showqrcode']",
                    "https://home.51cto.com/home")),
            Map.entry(PublicationPlatformType.EASTMONEY, new QrLoginSite(
                    "https://mp.eastmoney.com/", "#qrcode", "#qrcode",
                    "https://caifuhaoapi.eastmoney.com/api/v2/getauthorinfo")));

    @Resource
    private PlaywrightBrowserComponent browserComponent;
    private final BrowserTaskRunner taskRunner = new BrowserTaskRunner("qr-platform-login-browser");

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
    private QrLoginSite activeQrSite;
    private Instant startedAt;
    private DouyinIdentityVerificationMethod douyinVerificationMethod;
    /** 51CTO 本次微信扫码标识，轮询扫码结果时使用。 */
    private String cto51ScanSid;

    /** CSDN、什么值得买的微信二维码位于平台原生登录页，不走外部授权客户端。 */
    public boolean handlesScanMethod(PublicationPlatformType platform, PlatformScanLoginMethod method) {
        return supportsPlatform(platform) && (method == PlatformScanLoginMethod.PLATFORM_APP
                || method == PlatformScanLoginMethod.WECHAT
                && (platform == PublicationPlatformType.CSDN || platform == PublicationPlatformType.SMZDM));
    }

    /**
     * 判断平台是否已有扫码页和账号核验规则，供服务层决定是否开放登录入口。
     *
     * @param platform 目标平台
     * @return 是否有完整的扫码登录定义
     */
    public static boolean supportsPlatform(PublicationPlatformType platform) {
        return LOGIN_SITES.containsKey(platform);
    }

    /** @return 当前平台是否正在执行原生扫码登录。 */
    public boolean isActiveFor(PublicationPlatformType platform) {
        return activePlatform == platform;
    }

    /** 返回扫码平台已核对的登录入口，供同平台第三方授权复用。 */
    static String loginUrl(PublicationPlatformType platform) {
        QrLoginSite site = LOGIN_SITES.get(platform);
        if (site == null) throw new PlatformBrowserLoginException("该平台尚未接入扫码登录");
        return site.loginUrl();
    }

    /**
     * 打开目标平台扫码页并返回二维码；同一时间仅保留一个平台的扫码任务。
     *
     * @param platform 具有独立扫码定义的平台
     * @return 等待扫码的进度和图片，不包含会话 JSON
     */
    public BrowserLoginSnapshot startLogin(PublicationPlatformType platform) {
        return startLogin(platform, requireSite(platform));
    }

    /**
     * 打开 CSDN 微信扫码页；微信授权只启动登录，最终账号仍由 CSDN 创作后台确认。
     *
     * @return 等待扫码的状态与微信二维码
     */
    public BrowserLoginSnapshot startCsdnWechatLogin() {
        return startLogin(PublicationPlatformType.CSDN, CSDN_WECHAT_SITE);
    }

    /**
     * 按扫码来源打开目标平台登录页，保持单任务互斥与账号核验流程一致。
     *
     * @param platform 目标平台
     * @param site 本次扫码来源的页面与二维码定位规则
     * @return 等待扫码的状态与二维码
     */
    private BrowserLoginSnapshot startLogin(PublicationPlatformType platform, QrLoginSite site) {
        return onBrowserThread(() -> {
            closeActiveLogin();
            try {
                // 1. 为目标平台创建隔离浏览器，不读取本机 Chrome 或其他平台的登录态。
                activeSession = browserComponent.openBrowser(
                        new Browser.NewContextOptions().setViewportSize(900, 720));
                activeContext = activeSession.context();
                activePage = activeSession.newPage();
                activePage.setDefaultTimeout(15_000);
                // 2. 仅在二维码图片实际加载后返回，避免页面先渲染占位图。
                log.info("打开平台扫码页，platformType={}", platform);
                activePage.navigate(site.loginUrl(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
                if (site.qrToggleSelector() != null) {
                    activePage.locator(site.qrToggleSelector()).first().click();
                }
                if (platform == PublicationPlatformType.CTO_51) {
                    openCto51WechatQrCode();
                }
                log.info("平台扫码页已打开，等待二维码，platformType={}", platform);
                qrImage(site.qrImageSelector(), platform).waitFor();
                log.info("平台二维码已出现，等待图片加载，platformType={}", platform);
                int requiredImages = platform == PublicationPlatformType.XIAOHONGSHU ? 2 : 1;
                if (platform == PublicationPlatformType.EASTMONEY) {
                    // 东方财富创作平台将登录页嵌在跨域 iframe，必须在 iframe 中核对真实码宽度。
                    activePage.frames().stream()
                            .filter(frame -> frame.url().contains("exaccount2.eastmoney.com/Home/Login4"))
                            .findFirst().orElseThrow(() -> new PlatformBrowserLoginException("东方财富登录页面未加载"))
                            .waitForFunction("() => { const image = document.querySelector('#qrcode');"
                                    + " return image?.complete && image.naturalWidth >= 100; }");
                } else {
                    activePage.waitForFunction("() => { const images = document.querySelectorAll(\""
                            + site.qrImageSelector() + "\"); if (images.length < " + requiredImages
                            + ") return false; const image = images["
                            + (platform == PublicationPlatformType.DOUYIN ? "0" : "images.length - 1") + "];"
                            + " return image instanceof HTMLCanvasElement ? image.width >= 100"
                            + " : image?.complete && image.naturalWidth >= 100; }");
                }
                log.info("平台二维码图片已加载，platformType={}", platform);
                // 3. 记录平台与任务期限，旧任务的定时清理不得影响新任务。
                activePlatform = platform;
                activeQrSite = site;
                startedAt = Instant.now();
                Instant loginStartedAt = startedAt;
                taskRunner.schedule(() -> {
                    if (loginStartedAt.equals(startedAt)) closeActiveLogin();
                }, LOGIN_LIFETIME.toSeconds(), TimeUnit.SECONDS);
                return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            } catch (PlatformBrowserLoginException exception) {
                closeActiveLogin();
                throw exception;
            } catch (PlaywrightException exception) {
                // 关闭浏览器前截取失败现场，供用户对照页面判断原因
                PlatformBrowserLoginException failure = PlatformLoginPageFailure.toException(activePage, platform, exception);
                closeActiveLogin();
                throw failure;
            }
        });
    }

    /**
     * 查询当前平台扫码结果；抖音创作者主页可直接确认账号，其余情况继续查询账号接口。
     *
     * @param platform 当前弹窗选择的平台
     * @return 登录进度、图片和仅供服务层落库的会话
     */
    public BrowserLoginSnapshot readLoginProgress(PublicationPlatformType platform) {
        requireSite(platform);
        return onBrowserThread(() -> {
            if (activeContext == null || activePlatform != platform) {
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.NOT_STARTED, null, null, null);
            }
            if (startedAt.plus(LOGIN_LIFETIME).isBefore(Instant.now())) {
                closeActiveLogin();
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.EXPIRED, null, null, null);
            }
            // 微信扫码可能进入小程序后台；账号类型不符时不能保存为公众号登录态。
            if (platform == PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT
                    && activePage.getByText("小程序开发与发布流程", new Page.GetByTextOptions().setExact(true)).count() > 0) {
                throw new PlatformBrowserLoginException("本次进入的是微信小程序后台，不是微信公众号；请使用有公众号管理权限的微信扫码");
            }
            // 1. 页面跳转或 Cookie 存在本身不能算登录成功；抖音主页还须读到当前账号标识。
            // CSDN 扫码页未跳转前仍在等待；避免每次轮询都重新加载创作后台。
            if (platform == PublicationPlatformType.CSDN
                    && activePage.url().startsWith("https://passport.csdn.net/login")) {
                return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            }
            // 51CTO 二维码页仍在显示时先查扫码结果，确认后跳转登录地址写入 Cookie，再走账号接口
            if (platform == PublicationPlatformType.CTO_51 && activePage.url().startsWith(WECHAT_SHOW_QR_CODE_URL)) {
                BrowserLoginSnapshot waiting = confirmCto51WechatScan();
                if (waiting != null) return waiting;
            }
            AccountProbe probe = platform == PublicationPlatformType.DOUYIN
                    ? probeDouyinPageAccount(activePage) : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            // 2. 页面不能确认时再用原账号接口，保留其他平台的既有判定。
            if (probe.status() != PlatformBrowserLoginStatus.LOGGED_IN) {
                probe = probeAccount(platform, activeContext);
            }
            // 3. 只有拿到明确账号标识时才导出本次会话，供服务层保存。
            if (probe.status() == PlatformBrowserLoginStatus.LOGGED_IN) {
                String storageState = activeSession.exportStorageState();
                closeActiveLogin();
                return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.LOGGED_IN,
                        probe.accountName(), null, storageState);
            }
            return snapshotWithStatus(probe.status() == PlatformBrowserLoginStatus.UNKNOWN
                    ? PlatformBrowserLoginTaskStatus.UNKNOWN : PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
        });
    }

    /**
     * 取 51CTO 微信公众号二维码并在当前页单独打开，供后续截图展示。
     * <p>51CTO 登录页的脚本放在静态资源域名上，自动化浏览器访问会被对方防护拦截，页面上的二维码不会出现；
     * 这里按登录页脚本的同一流程调用取码接口（与登录页共用 Cookie），只记录 sid，不保存二维码。</p>
     *
     * @throws PlatformBrowserLoginException 取码接口未返回有效的微信二维码
     */
    private void openCto51WechatQrCode() {
        // 1. 调用登录页同一个取码接口，code 为 200 才有 sid 和二维码地址
        Object qrCode = requestCto51Json(CTO51_QR_CODE_URL);
        if (qrCode == null) {
            throw new PlatformBrowserLoginException("51CTO 微信二维码获取失败，请稍后重试");
        }
        String sid = readAccountText(qrCode, "$.data.sid", "");
        String qrCodeUrl = readAccountText(qrCode, "$.data.img", "");
        // 2. 只接受微信公众号二维码地址，避免打开接口返回的其他页面
        if (readAccountNumber(qrCode, "$.code", -1) != 200 || sid.isBlank()
                || !qrCodeUrl.startsWith(WECHAT_SHOW_QR_CODE_URL)) {
            throw new PlatformBrowserLoginException("51CTO 微信二维码获取失败，请稍后重试");
        }
        // 3. 记录 sid 供轮询使用，并打开二维码图片供截图
        cto51ScanSid = sid;
        activePage.navigate(qrCodeUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
    }

    /**
     * 查询 51CTO 微信扫码结果；用户确认后打开登录地址，由 51CTO 写入登录 Cookie。
     * <p>判定规则与登录页脚本一致：code 不是 200 表示二维码已失效；data.isok 为 1 表示已扫码确认。</p>
     *
     * @return 仍在等待或二维码失效时返回要展示的进度；已确认并完成跳转时返回 null，由调用方继续核验账号
     */
    private BrowserLoginSnapshot confirmCto51WechatScan() {
        // 1. 按 sid 查询扫码结果；本次没读到结果下次再查，接口明确返回非 200 才算二维码失效
        Object scanResult = requestCto51Json(CTO51_SCAN_PULL_URL + cto51ScanSid + "&time=" + System.currentTimeMillis());
        if (scanResult == null) {
            return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
        }
        if (readAccountNumber(scanResult, "$.code", -1) != 200) {
            closeActiveLogin();
            return new BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus.EXPIRED, null, null, null);
        }
        // 2. 未确认时继续展示二维码，不请求账号接口
        if (!"1".equals(readAccountText(scanResult, "$.data.isok", ""))) {
            return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
        }
        // 3. 已确认：打开登录页脚本跳转的同一地址，Cookie 写入后由账号接口确认身份
        activePage.navigate(CTO51_LOGIN_URL, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
        return null;
    }

    /**
     * 在抖音扫码后的身份验证页选择用户指定的短信流程；不代替用户完成验证。
     *
     * @param method 用户选择的验证方式
     * @return 验证页面截图和等待状态
     */
    public BrowserLoginSnapshot selectDouyinVerificationMethod(DouyinIdentityVerificationMethod method) {
        return onBrowserThread(() -> {
            requireDouyinVerificationPage();
            try {
                // 只点击抖音自身的验证入口，后续短信或手机操作仍由用户完成。
                String option = method == DouyinIdentityVerificationMethod.RECEIVE_SMS
                        ? "接收短信验证码" : "发送短信验证";
                douyinVerificationFrame("text=" + option).locator("text=" + option).first().click();
                douyinVerificationMethod = method;
                return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException("抖音身份验证方式打开失败，请查看页面提示", exception);
            }
        });
    }

    /**
     * 把用户收到的短信码输入抖音当前验证页；是否登录仍由账号接口确认。
     *
     * @param code 用户收到的短信验证码
     * @return 提交后的页面截图和等待状态
     */
    public BrowserLoginSnapshot submitDouyinVerificationCode(String code) {
        return onBrowserThread(() -> {
            requireDouyinVerificationPage();
            if (douyinVerificationMethod != DouyinIdentityVerificationMethod.RECEIVE_SMS) {
                throw new PlatformBrowserLoginException("请先选择接收短信验证码");
            }
            try {
                // 验证码只进入当前隔离浏览器，不写入会话表或日志。
                Frame frame = douyinVerificationFrame("input[placeholder*='验证码']:visible");
                frame.locator("input[placeholder*='验证码']:visible").last().fill(code);
                frame.locator("button:has-text('确认'), button:has-text('确定'), button:has-text('提交'), "
                        + "button:has-text('下一步'), button:has-text('完成'), button:has-text('验证')")
                        .last().click();
                return snapshotWithStatus(PlatformBrowserLoginTaskStatus.LOGIN_PENDING);
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException("抖音短信验证码提交失败，请查看页面提示", exception);
            }
        });
    }

    /** 当前扫码任务必须已进入抖音身份验证，禁止操作其他平台或已结束的会话。 */
    private void requireDouyinVerificationPage() {
        if (activePlatform != PublicationPlatformType.DOUYIN || activePage == null
                || douyinInteractionStage() == null) {
            throw new PlatformBrowserLoginException("当前没有待完成的抖音身份验证");
        }
    }

    /** 识别抖音二次验证阶段；扫码本身不是账号登录成功。 */
    private PlatformLoginInteractionStage douyinInteractionStage() {
        if (activePlatform != PublicationPlatformType.DOUYIN || activePage == null) return null;
        if (douyinVerificationMethod == DouyinIdentityVerificationMethod.RECEIVE_SMS) {
            return PlatformLoginInteractionStage.DOUYIN_SMS_CODE;
        }
        if (douyinVerificationMethod == DouyinIdentityVerificationMethod.SEND_SMS) {
            return PlatformLoginInteractionStage.DOUYIN_SEND_SMS;
        }
        return hasVisibleDouyinVerification("text=身份验证")
                ? PlatformLoginInteractionStage.DOUYIN_IDENTITY_VERIFICATION : null;
    }

    /** 抖音风控弹窗可能放在内嵌框架中，仅选取实际可见的验证控件。 */
    private Frame douyinVerificationFrame(String selector) {
        for (Frame frame : activePage.frames()) {
            try {
                Locator control = frame.locator(selector).first();
                if (control.count() > 0 && control.isVisible()) return frame;
            } catch (PlaywrightException ignored) {
                // 页面跳转中的旧框架不可操作，继续寻找当前仍可见的验证控件。
            }
        }
        throw new PlatformBrowserLoginException("抖音验证控件未出现，请查看当前页面提示");
    }

    /** 判断扫码后是否真的出现身份验证，而非只凭扫码或 Cookie 推断已登录。 */
    private boolean hasVisibleDouyinVerification(String selector) {
        try {
            douyinVerificationFrame(selector);
            return true;
        } catch (PlatformBrowserLoginException exception) {
            return false;
        }
    }

    /**
     * 在新的隔离浏览器上下文恢复数据库会话；抖音先检查账号主页，再查询平台账号接口。
     *
     * @param platform 数据库会话所属平台
     * @param storageState 数据库保存的 Playwright 会话
     * @return 最新账号核验结论
     */
    public AccountProbe verifySavedLogin(PublicationPlatformType platform, String storageState) {
        requireSite(platform);
        return onBrowserThread(() -> {
            try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                    new Browser.NewContextOptions().setStorageState(storageState))) {
                // 抖音恢复会话后优先检查本人主页，避免旧账号接口返回格式变化误报失效。
                if (platform == PublicationPlatformType.DOUYIN) {
                    AccountProbe pageAccount = probeSavedDouyinPageAccount(session.context());
                    if (pageAccount.status() == PlatformBrowserLoginStatus.LOGGED_IN) return pageAccount;
                }
                // 页面没有账号标识时仍沿用平台账号接口，不凭 Cookie 作成功结论。
                return probeAccount(platform, session.context());
            } catch (PlaywrightException exception) {
                throw new PlatformBrowserLoginException(platform.getDisplayName()
                        + "登录态检测失败，请检查浏览器和网络", exception);
            }
        });
    }

    /**
     * 恢复抖音会话后重新打开创作者主页，避免旧账号接口格式变化使已登录账号显示无法确认。
     *
     * @param context 从数据库会话恢复的隔离浏览器
     * @return 页面确认的抖音账号，或无法确认
     */
    private AccountProbe probeSavedDouyinPageAccount(BrowserContext context) {
        try (Page page = context.newPage()) {
            // 恢复的会话必须重新进入创作者主页，不能复用登录时的页面截图。
            page.navigate("https://creator.douyin.com/creator-micro/home", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
            page.waitForFunction("() => /抖音号\\s*[：:]\\s*[A-Za-z0-9._-]{3,32}/.test(document.body?.innerText || '')",
                    new Page.WaitForFunctionOptions().setTimeout(8_000));
            // 只有页面地址与抖音号同时匹配，才能返回已登录。
            return probeDouyinPageAccount(page);
        } catch (PlaywrightException exception) {
            log.debug("抖音已保存会话的主页暂时无法确认，errorType={}", exception.getClass().getSimpleName());
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 仅抖音创作者主页明确展示当前抖音号时确认登录；不能用一般首页跳转或 Cookie 猜测。
     *
     * @param page 当前扫码或恢复的页面
     * @return 页面账号结论
     */
    private AccountProbe probeDouyinPageAccount(Page page) {
        try {
            return parseDouyinPageAccount(page.url(), page.locator("body").innerText());
        } catch (PlaywrightException exception) {
            log.debug("抖音主页账号暂时无法读取，errorType={}", exception.getClass().getSimpleName());
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 将抖音主页地址与账号标识同时作为页面登录证据，防止误把登录页当成账号主页。
     *
     * @param pageUrl 当前页面地址
     * @param pageText 当前页面可见文字
     * @return 可确认的账号，或无法确认
     */
    AccountProbe parseDouyinPageAccount(String pageUrl, String pageText) {
        try {
            URI uri = URI.create(pageUrl);
            // 账号标识必须来自抖音创作者主页；登录页中的任何相同文字都不能作为登录证据。
            if (!"creator.douyin.com".equals(uri.getHost())
                    || !uri.getPath().startsWith("/creator-micro/home")) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            Matcher account = DOUYIN_PAGE_ACCOUNT_ID.matcher(pageText == null ? "" : pageText);
            return account.find()
                    ? new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, account.group(1))
                    : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        } catch (IllegalArgumentException exception) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 取消目标平台当前扫码，不删除数据库里已有的登录态。
     *
     * @param platform 当前弹窗选择的平台
     */
    public void cancelLogin(PublicationPlatformType platform) {
        requireSite(platform);
        onBrowserThread(() -> {
            if (activePlatform == platform) closeActiveLogin();
            return null;
        });
    }

    /** 应用退出时在浏览器工作线程关闭当前任务。 */
    @PreDestroy
    public void shutdown() {
        taskRunner.shutdown(this::closeActiveLogin);
    }

    /**
     * 用扫码或恢复的上下文查询平台账号；网络异常不当作明确失效。
     *
     * @param platform 会话所属平台
     * @param context 当前浏览器上下文
     * @return 登录、未登录或未知结论
     */
    AccountProbe probeAccount(PublicationPlatformType platform, BrowserContext context) {
        // 1. 需页面动态签名的平台由页面发起请求，其余平台直接访问受保护账号接口
        if (platform == PublicationPlatformType.CSDN) {
            return probeCsdnAccount(context);
        }
        if (platform == PublicationPlatformType.EASTMONEY) {
            return probeEastmoneyAccount(context);
        }
        try {
            RequestOptions requestOptions = RequestOptions.create().setTimeout(10_000);
            if (platform == PublicationPlatformType.XIAOHONGSHU) {
                requestOptions.setHeader("Origin", "https://creator.xiaohongshu.com")
                        .setHeader("Referer", "https://creator.xiaohongshu.com/");
            }
            if (platform == PublicationPlatformType.SMZDM) {
                // 什么值得买账号接口不带站内来源时，已登录也返回 smzdm_id=0，必须带上站内 Referer
                requestOptions.setHeader("Referer", "https://www.smzdm.com/");
            }
            APIResponse response = context.request().get(requireSite(platform).accountUrl(), requestOptions);
            // 2. HTTP 认证拒绝优先；知乎访问限制只标记未知，避免误判会话失效
            if (platform == PublicationPlatformType.ZHIHU && response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            if (response.status() == 401 || response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            if (!response.ok()) {
                return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
            }
            // 3. 按平台解析 HTML 或 JSON，再由平台规则判定身份，解析失败返回未知
            if (platform == PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT) {
                return parseWechatAccountHtml(new String(response.body(), StandardCharsets.UTF_8));
            }
            if (platform == PublicationPlatformType.WEIBO) {
                return parseWeiboAccountHtml(new String(response.body(), StandardCharsets.UTF_8));
            }
            if (platform == PublicationPlatformType.WOSHIPM) {
                return probeWoshipmAccount(context, response);
            }
            if (platform == PublicationPlatformType.CTO_51) {
                return parseCto51HomeHtml(response.url(), new String(response.body(), StandardCharsets.UTF_8));
            }
            return parseAccountResponse(platform, JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8)));
        } catch (JSONException | PlaywrightException exception) {
            log.debug("扫码平台账号核验暂时无法确认，platformType={} errorType={}",
                    platform, exception.getClass().getSimpleName());
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * CSDN 创作后台为用户接口动态签名，直接重放 GET 会被网关拒绝；由后台页面发起真实请求后读取账号响应。
     *
     * @param context 当前扫码或数据库恢复的浏览器上下文
     * @return 明确的账号、未登录或无法确认结论
     */
    private AccountProbe probeCsdnAccount(BrowserContext context) {
        try (Page page = context.newPage()) {
            // 1. 只读取创作后台自己发出的用户接口；页面标题、Cookie 和跳转均不视为登录成功
            Response response = page.waitForResponse(
                    candidate -> candidate.url().startsWith(CSDN_ACCOUNT_URL),
                    new Page.WaitForResponseOptions().setTimeout(12_000),
                    () -> page.navigate("https://mp.csdn.net/mp_blog/manage/article",
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15_000)));
            // 2. 优先区分认证拒绝，其他 HTTP 失败保持未知
            if (response.status() == 401 || response.status() == 403) {
                return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
            }
            // 3. 业务账号由平台专用规则核验，页面关闭后仍只返回核验结论
            return response.ok() ? parseAccountResponse(PublicationPlatformType.CSDN,
                    JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8)))
                    : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        } catch (JSONException | PlaywrightException exception) {
            log.debug("CSDN 创作后台账号核验暂时无法确认，errorType={}", exception.getClass().getSimpleName());
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
    }

    /**
     * 由东方财富创作页生成登录令牌并请求作者信息，不能用静态空令牌代替。
     * @param context 当前会话的浏览器上下文
     * @return 作者账号核验结论，页面或 JSON 失败为未知
     */
    private AccountProbe probeEastmoneyAccount(BrowserContext context) {
        try (Page page = context.newPage()) {
            // 页面脚本负责附加创作平台令牌；只等待这次页面实际收到的作者响应
            Response response = page.waitForResponse(
                    candidate -> candidate.url().startsWith(requireSite(PublicationPlatformType.EASTMONEY).accountUrl()),
                    new Page.WaitForResponseOptions().setTimeout(12_000),
                    () -> page.navigate("https://mp.eastmoney.com/",
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15_000)));
            // HTTP 成功后再核验作者资料，页面或解析异常不能当成明确登录
            return response.ok() ? parseEastmoneyAccount(JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8)))
                    : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        } catch (JSONException | PlaywrightException exception) {
            log.debug("东方财富创作平台账号核验暂时无法确认，errorType={}", exception.getClass().getSimpleName());
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
    private AccountProbe probeWoshipmAccount(BrowserContext context, APIResponse response) {
        // 1. 确认写作页地址并提取当前用户标识，公开主页不是登录证据
        if (!response.url().contains("/writing")) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        Matcher account = WOSHIPM_ACCOUNT.matcher(new String(response.body(), StandardCharsets.UTF_8));
        if (!account.find()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        // 2. 继续请求个人资料，HTTP 和业务码均成功后才读取账号
        APIResponse profile = context.request().get("https://www.woshipm.com/api2/user/profile?uid=" + account.group(1),
                RequestOptions.create().setTimeout(10_000).setHeader("X-Requested-With", "XMLHttpRequest"));
        if (!profile.ok()) return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        Object profileResponse = JSON.parseObject(new String(profile.body(), StandardCharsets.UTF_8));
        if ((int) readAccountNumber(profileResponse, "$.CODE", -1) != 200) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 3. 资料账号必须与写作页标识一致，避免把其他作者资料当成当前会话
        Object user = JSONPath.eval(profileResponse, "$.RESULT.userInfoVo");
        String accountId = readAccountText(user, "$.uid", "");
        String accountName = readAccountText(user, "$.nickName", "");
        return accountId.isBlank() || !accountId.equals(account.group(1))
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 作者接口成功且同时带用户 ID、昵称时才确认东方财富登录。
     * @param response 平台作者响应
     * @return 账号核验结论
     */
    AccountProbe parseEastmoneyAccount(JSONObject response) {
        // 未登录业务码优先于返回体，避免把公共主页误判为当前作者账号。
        if ((int) readAccountNumber(response, "$.Success", -1) == -1) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if ((int) readAccountNumber(response, "$.Success", -1) != 1) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object author = JSONPath.eval(response, "$.Result");
        String authorId = readAccountText(author, "$.relatedUid", "");
        String authorName = readAccountText(author, "$.nickName", "");
        return authorId.isBlank() || authorName.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, authorName);
    }

    /**
     * 分别解释各平台账号接口的业务码和账号标识，不共用 Cookie 存在性判定。
     *
     * @param platform 账号所属平台
     * @param response 已解析的账号响应
     * @return 可用于落库和更新状态的结论
     */
    AccountProbe parseAccountResponse(PublicationPlatformType platform, JSONObject response) {
        // 空响应没有身份依据，不能转交平台解析后误判成功
        if (response == null) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 每个平台独立解释业务码与账号字段，不共用成功码或账号路径
        return switch (platform) {
            case ZHIHU -> parseZhihuAccount(response);
            case JUEJIN -> parseJuejinAccount(response);
            case BILIBILI -> parseBilibiliAccount(response);
            case TOUTIAO -> parseToutiaoAccount(response);
            case XIAOHONGSHU -> parseXiaohongshuAccount(response);
            case DOUYIN -> parseDouyinAccount(response);
            case CSDN -> parseCsdnAccount(response);
            case SMZDM -> parseSmzdmAccount(response);
            default -> throw new PlatformBrowserLoginException("该平台尚未接入独立浏览器登录");
        };
    }

    /**
     * 知乎账号接口必须同时返回文本型账号 ID 和名称，才确认本次登录。
     * @param response 平台账号响应
     * @return 账号核验结论
     */
    private AccountProbe parseZhihuAccount(JSONObject response) {
        // 标识与名称都必须是非空文本，不将数字或容器强制转换为账号
        if (!(JSONPath.eval(response, "$.id") instanceof String) || !(JSONPath.eval(response, "$.name") instanceof String)
                || readAccountText(response, "$.id", "").isBlank() || readAccountText(response, "$.name", "").isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, readAccountText(response, "$.name", ""));
    }

    /**
     * 微信公众平台返回 HTML；只认非空后台 token 与账号昵称，登录页占位值不算成功。
     *
     * @param html 使用当前浏览器会话访问平台首页得到的 HTML
     * @return 微信公众号账号结论
     */
    AccountProbe parseWechatAccountHtml(String html) {
        Matcher token = WECHAT_TOKEN.matcher(html);
        Matcher account = WECHAT_ACCOUNT.matcher(html);
        if (token.find() && account.find()) {
            return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN, account.group(1));
        }
        // 明确回到带扫码区的登录页才判未登录；其他页面结构变化保留未知。
        return new AccountProbe(html.contains("login__type__container__scan__qrcode")
                ? PlatformBrowserLoginStatus.NOT_LOGGED_IN : PlatformBrowserLoginStatus.UNKNOWN, null);
    }

    /**
     * 微博文章编辑器将当前账号放在页面配置中；未登录的跳转页不包含该配置。
     *
     * @param html 微博文章编辑器响应
     * @return 账号或未登录结论
     */
    AccountProbe parseWeiboAccountHtml(String html) {
        // 只提取编辑器中的当前账号配置，跳转到公共登录页不能确认身份
        Matcher config = WEIBO_CONFIG.matcher(html);
        if (!config.find()) {
            return new AccountProbe(html.contains("url=https://weibo.com/")
                    ? PlatformBrowserLoginStatus.NOT_LOGGED_IN : PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 解析配置后仍要求非空账号标识；没有昵称时使用标识展示
        String configJson = config.group(1).replace("\\'", "'").replace("\\\\", "\\");
        Object account = JSON.parseObject(configJson);
        String accountId = readAccountText(account, "$.uid", "");
        if (accountId.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(account, "$.nick", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 掘金未登录也会返回业务成功码，必须继续检查 data 中的用户 ID。
     *
     * @param response 掘金账号响应
     * @return 掘金账号结论
     */
    private AccountProbe parseJuejinAccount(JSONObject response) {
        // 保留 data 缺失与显式空值的区别：成功码加显式空值是访客，缺失字段是未知
        Object account = JSONPath.eval(response, "$.data");
        if ((int) readAccountNumber(response, "$.err_no", -1) == 0 && account == null && response.containsKey("data")) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if ((int) readAccountNumber(response, "$.err_no", -1) != 0 || !response.containsKey("data")) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 成功响应还必须含用户标识，不能仅凭业务成功码确认登录
        String accountId = readAccountText(account, "$.user_id", null);
        if (accountId == null || accountId.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(account, "$.user_name", null);
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName == null || accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 哔哩哔哩账号接口 HTTP 成功不代表登录；业务码与 mid 必须同时有效。
     *
     * @param response 哔哩哔哩账号响应
     * @return 哔哩哔哩账号结论
     */
    private AccountProbe parseBilibiliAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        int code = (int) readAccountNumber(response, "$.code", Integer.MIN_VALUE);
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.data");
        // 未登录业务码优先；其他非成功码属于无法确认，不能误标为失效。
        if (code == -101 || code == 0 && !readAccountBoolean(account, "$.isLogin")) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (code != 0 || !readAccountBoolean(account, "$.isLogin")
                || readAccountNumber(account, "$.mid", 0) <= 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(account, "$.uname", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? readAccountText(account, "$.mid", "") : accountName);
    }

    /**
     * 头条号未登录的业务码不能与成功响应混淆；只接受包含用户 ID 的账号。
     *
     * @param response 头条号账号响应
     * @return 头条号账号结论
     */
    private AccountProbe parseToutiaoAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        if ((int) readAccountNumber(response, "$.err_no", -1) == 100004) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if ((int) readAccountNumber(response, "$.err_no", -1) != 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.data.user");
        String accountId = readAccountText(account, "$.id", null);
        if (accountId == null || accountId.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(account, "$.screen_name", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 小红书创作者平台只有明确返回账号标识时才保存会话。
     *
     * @param response 创作者账号接口响应
     * @return 登录结论与账号名
     */
    private AccountProbe parseXiaohongshuAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        if (!readAccountBoolean(response, "$.success")) {
            return (int) readAccountNumber(response, "$.result", 0) == -100
                    ? new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null)
                    : new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.data");
        String accountId = readAccountText(account, "$.userId", "");
        if (accountId.isBlank()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = readAccountText(account, "$.userName", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? accountId : accountName);
    }

    /**
     * 抖音创作者账号接口必须同时返回成功码与当前用户标识。
     * @param response 平台账号响应
     * @return 账号核验结论
     */
    private AccountProbe parseDouyinAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        int statusCode = (int) readAccountNumber(response, "$.status_code", -1);
        if (statusCode == 8) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (statusCode != 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.user_info");
        String accountId = readAccountText(account, "$.uid", "");
        if (accountId.isBlank()) {
            account = JSONPath.eval(response, "$.data");
            accountId = readAccountText(account, "$.user_id", "");
        }
        String accountName = readAccountText(account, "$.nickname", "");
        return accountId.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        accountName.isBlank() ? accountId : accountName);
    }

    /**
     * CSDN 创作后台业务成功码与用户名同时存在时，才确认当前账号。
     *
     * @param response 创作后台用户接口响应
     * @return 登录结论与账号名
     */
    private AccountProbe parseCsdnAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        if ((int) readAccountNumber(response, "$.code", -1) != 200) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        Object account = JSONPath.eval(response, "$.data");
        String username = readAccountText(account, "$.username", "");
        String nickname = readAccountText(account, "$.nickname", "");
        return username.isBlank()
                ? new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null)
                : new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                        nickname.isBlank() ? username : nickname);
    }

    /**
     * 什么值得买未登录也返回 HTTP 200；只有正数用户 ID 才代表当前登录账号。
     *
     * @param response 站点当前用户接口响应
     * @return 登录结论与账号名
     */
    private AccountProbe parseSmzdmAccount(JSONObject response) {
        // 先核对平台业务状态，明确未登录与无法确认分别处理
        long accountId = readAccountNumber(response, "$.smzdm_id", -1);
        if (accountId == 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        if (accountId < 0) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        // 再核验响应中的账号资料；业务成功不等于已有有效身份标识
        String accountName = readAccountText(response, "$.nickname", "");
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? Long.toString(accountId) : accountName);
    }

    /**
     * 以登录页 Ajax 的方式请求 51CTO 接口，与登录页共用浏览器 Cookie。
     *
     * @param url 接口地址
     * @return 解析后的 JSON；HTTP 失败或返回的不是 JSON（如被防护拦截的页面）时为 null
     */
    private Object requestCto51Json(String url) {
        APIResponse response = activeContext.request().get(url, RequestOptions.create().setTimeout(10_000)
                .setHeader("X-Requested-With", "XMLHttpRequest").setHeader("Referer", "https://home.51cto.com/index"));
        if (!response.ok()) return null;
        try {
            return JSON.parseObject(new String(response.body(), StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            return null;
        }
    }

    /**
     * 51CTO 个人首页只有登录后才能打开，未登录会跳回登录页 /index；以页面顶部的账号链接确认身份。
     * <p>账号接口 api-media.51cto.com 会拦截自动化浏览器（返回 567），因此改读 home.51cto.com 个人首页。</p>
     *
     * @param responseUrl 跟随跳转后的最终地址
     * @param html 个人首页或登录页 HTML
     * @return 已登录（昵称或用户 ID）、未登录，或页面结构不符时无法确认
     */
    AccountProbe parseCto51HomeHtml(String responseUrl, String html) {
        // 1. 跳回登录页是平台对未登录会话的明确响应
        if ("/index".equals(URI.create(responseUrl).getPath())) {
            return new AccountProbe(PlatformBrowserLoginStatus.NOT_LOGGED_IN, null);
        }
        // 2. 个人首页须带当前账号链接，昵称为空时以用户 ID 展示
        Matcher account = CTO51_ACCOUNT.matcher(html);
        if (!account.find()) {
            return new AccountProbe(PlatformBrowserLoginStatus.UNKNOWN, null);
        }
        String accountName = account.group(2).strip();
        return new AccountProbe(PlatformBrowserLoginStatus.LOGGED_IN,
                accountName.isBlank() ? account.group(1) : accountName);
    }

    /**
     * 截取当前二维码；扫码后页面切换而账号仍无法确认时显示整页提示。
     *
     * @param status 当前登录任务进度
     * @return 页面可展示的图片和进度
     */
    private BrowserLoginSnapshot snapshotWithStatus(PlatformBrowserLoginTaskStatus status) {
        byte[] screenshot;
        PlatformLoginInteractionStage interactionStage = douyinInteractionStage();
        try {
            screenshot = interactionStage == null
                    ? qrImage(activeQrSite.qrScreenshotSelector(), activePlatform).screenshot()
                    : activePage.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        } catch (PlaywrightException exception) {
            screenshot = activePage.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        }
        return new BrowserLoginSnapshot(status, null, Base64.getEncoder().encodeToString(screenshot), null,
                interactionStage);
    }

    /** 抖音只取创作者登录区的码，不触发 AI 工坊等其他业务入口。 */
    private Locator qrImage(String selector, PublicationPlatformType platform) {
        if (platform == PublicationPlatformType.EASTMONEY) {
            return activePage.frameLocator("iframe[src*='exaccount2.eastmoney.com/Home/Login4']")
                    .locator(selector);
        }
        Locator images = activePage.locator(selector);
        return platform == PublicationPlatformType.DOUYIN ? images.first() : images.last();
    }

    /** 按上下文、浏览器、驱动顺序关闭当前扫码任务。 */
    private void closeActiveLogin() {
        if (activeSession != null) activeSession.close();
        activePage = null;
        activeContext = null;
        activeSession = null;
        activePlatform = null;
        activeQrSite = null;
        startedAt = null;
        douyinVerificationMethod = null;
        cto51ScanSid = null;
    }

    /**
     * 拒绝无扫码页或账号接口定义的平台，不能把任意平台导入通用扫码流程。
     *
     * @param platform 目标平台
     * @return 对应平台的扫码定义
     */
    private QrLoginSite requireSite(PublicationPlatformType platform) {
        QrLoginSite site = LOGIN_SITES.get(platform);
        if (site == null) {
            throw new PlatformBrowserLoginException("该平台尚未接入独立浏览器登录");
        }
        return site;
    }

    /**
     * Playwright 对象只在串行浏览器线程操作，防止不同接口并发使用同一会话。
     *
     * @param action 本次浏览器动作
     * @return 动作结果
     */
    private <T> T onBrowserThread(Callable<T> action) {
        return taskRunner.execute(action, LOGIN_FAILURE);
    }

    /** 平台扫码页、二维码与账号接口的固定地址，不接受来自页面的任意 URL。 */
    private record QrLoginSite(String loginUrl, String qrImageSelector, String qrScreenshotSelector,
                               String accountUrl, String qrToggleSelector) {

        /** 无需先切换扫码模式的平台使用四参数定义。 */
        QrLoginSite(String loginUrl, String qrImageSelector, String qrScreenshotSelector, String accountUrl) {
            this(loginUrl, qrImageSelector, qrScreenshotSelector, accountUrl, null);
        }
    }

    /** 内部登录进度；storageState 只供服务层保存，不返回前端。 */
    public record BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName,
                                       String screenshotBase64, String storageState,
                                       PlatformLoginInteractionStage interactionStage) {
        /** 未进入额外交互阶段的扫码结果。 */
        public BrowserLoginSnapshot(PlatformBrowserLoginTaskStatus status, String accountName,
                                    String screenshotBase64, String storageState) {
            this(status, accountName, screenshotBase64, storageState, null);
        }
    }

    /** 平台账号接口给出的明确登录结论。 */
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

    /**
     * 读取平台明确声明的成功标志，字段缺失时不能推定登录成功。
     * @param response 平台响应或账号子对象
     * @param path 字段路径
     * @return 是否明确成功
     */
    private static boolean readAccountBoolean(Object response, String path) {
        Object value = JSONPath.eval(response, path);
        return Boolean.TRUE.equals(value) || "true".equals(value)
                || value instanceof Number number && number.intValue() != 0;
    }

}
