package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 平台扫码入口的类型；第三方扫码完成后仍须核验目标平台账号。
 */
public enum PlatformScanLoginMethod {

    /** 使用目标平台自己的 App 扫码。 */
    PLATFORM_APP,

    /** 使用微信授权登录目标平台。 */
    WECHAT,

    /** 使用 QQ 授权登录目标平台。 */
    QQ
}
