package org.huangry.colorful.geo.infrastructure.client.login;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;

import java.util.Base64;

/**
 * 平台登录页打开失败时给用户看的统一提示和页面截图。
 * <p>扫码、表单、第三方授权三种登录客户端在打开登录页失败、关闭浏览器之前调用：截取当时的页面，
 * 登录弹窗展示截图，由用户判断是滑块验证、网络异常还是页面改版，不在代码里逐家识别失败原因。</p>
 * <p>约束：只读取页面，不点击、不绕过验证；截图是登录前页面，只随本次失败响应返回，不落库、不写日志。</p>
 */
@Slf4j
final class PlatformLoginPageFailure {

    private PlatformLoginPageFailure() {
    }

    /**
     * 根据失败时的页面生成登录异常。
     *
     * @param page 打开失败的登录页；浏览器未创建成功时为空
     * @param platform 目标平台
     * @param cause 底层浏览器异常
     * @return 登录异常：截到页面时提示对照截图确认原因并附截图，截不到时提示检查网络
     */
    static PlatformBrowserLoginException toException(Page page, PublicationPlatformType platform, Throwable cause) {
        String screenshotBase64 = captureScreenshot(page, platform);
        // 有截图时让用户对照页面判断原因；截不到说明浏览器或网络本身没起来
        String message = screenshotBase64 != null
                ? platform.getDisplayName() + "登录页未能正常打开，请对照下方页面截图确认原因后重试"
                : platform.getDisplayName() + "登录页打开失败，请检查网络后重试";
        return new PlatformBrowserLoginException(message, cause, screenshotBase64);
    }

    /**
     * 截取登录页当前视口，作为失败现场；属于辅助诊断，失败时只记 warn 日志。
     *
     * @param page 登录页，可为空
     * @param platform 目标平台，仅用于日志
     * @return 截图（PNG Base64）；页面不存在或截图失败时为空
     */
    private static String captureScreenshot(Page page, PublicationPlatformType platform) {
        if (page == null || page.isClosed()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(page.screenshot(new Page.ScreenshotOptions().setFullPage(false)));
        } catch (PlaywrightException exception) {
            log.warn("登录页失败截图未能截取 platformType={} errorType={}", platform, exception.getClass().getSimpleName());
            return null;
        }
    }
}
