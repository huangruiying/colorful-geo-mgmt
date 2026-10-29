package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 独立浏览器登录流程异常；错误消息不得包含 Cookie、登录态或管理员口令。
 * <p>打开登录页失败时可附带当时的页面截图，只随本次失败响应返回页面，不落库、不写日志。</p>
 */
public class PlatformBrowserLoginException extends RuntimeException {

    /** 失败时登录页的页面截图（PNG Base64）；没有截图时为空。 */
    private final String screenshotBase64;

    public PlatformBrowserLoginException(String message) {
        this(message, null, null);
    }

    public PlatformBrowserLoginException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /**
     * 创建附带失败截图的登录异常，供登录弹窗展示页面卡住的位置。
     *
     * @param message 可直接展示给用户的失败原因
     * @param cause 底层浏览器异常
     * @param screenshotBase64 失败时登录页页面截图（PNG Base64），截图失败时传空
     */
    public PlatformBrowserLoginException(String message, Throwable cause, String screenshotBase64) {
        super(message, cause);
        this.screenshotBase64 = screenshotBase64;
    }

    /**
     * 返回失败时的登录页截图。
     *
     * @return 页面截图（PNG Base64）；没有截图时为空
     */
    public String getScreenshotBase64() {
        return screenshotBase64;
    }
}
