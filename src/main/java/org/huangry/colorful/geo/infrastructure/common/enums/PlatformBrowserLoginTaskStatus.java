package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 一次浏览器登录任务的进度；仅供打开登录页和查询本次登录结果使用，不作为账号会话状态落库。
 */
public enum PlatformBrowserLoginTaskStatus {

    /** 当前平台没有进行中的登录任务，例如用户取消后仍有进度查询到达。 */
    NOT_STARTED,
    /** 登录页面已打开，等待扫码、验证码或密码完成。 */
    LOGIN_PENDING,
    /** 本次登录已取得平台确认的账号与会话。 */
    LOGGED_IN,
    /** 本次登录任务已超时。 */
    EXPIRED,
    /** 本次登录暂时无法确认结果。 */
    UNKNOWN
}
