package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 独立浏览器登录流程异常；错误消息不得包含 Cookie、登录态或管理员口令。
 */
public class PlatformBrowserLoginException extends RuntimeException {

    public PlatformBrowserLoginException(String message) {
        super(message);
    }

    public PlatformBrowserLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
