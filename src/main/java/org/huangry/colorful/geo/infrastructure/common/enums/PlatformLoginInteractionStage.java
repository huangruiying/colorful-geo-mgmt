package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 平台登录过程中的交互阶段，供页面提示用户当前操作；不代表目标平台已确认登录。
 */
public enum PlatformLoginInteractionStage {

    /** 第三方授权页仍在等待用户完成扫码或确认。 */
    WAITING_FOR_THIRD_PARTY,

    /** 授权页面已回到目标平台，仍须等待目标平台账号核验。 */
    RETURNED_TO_PLATFORM,

    /** 授权窗口已关闭，目标平台账号尚未确认登录。 */
    INTERACTION_WINDOW_CLOSED,

    /** 抖音扫码后要求再次验证身份，尚未进入账号登录态。 */
    DOUYIN_IDENTITY_VERIFICATION,

    /** 抖音等待用户输入收到的短信验证码。 */
    DOUYIN_SMS_CODE,

    /** 抖音要求用户按页面提示从手机发送验证短信。 */
    DOUYIN_SEND_SMS
}
