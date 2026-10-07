package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 平台账号登录状态；只描述账号会话，不描述一次登录任务的进度。
 */
public enum PlatformBrowserLoginStatus {

    /** 未接入 */
    NOT_SUPPORTED,
    /** 未登录 */
    NOT_LOGGED_IN,
    /** 已登录 */
    LOGGED_IN,
    /** 已保存的登录态失效 */
    EXPIRED,
    /** 网络或风控使本次账号状态检测无法得出结论。 */
    UNKNOWN
}
