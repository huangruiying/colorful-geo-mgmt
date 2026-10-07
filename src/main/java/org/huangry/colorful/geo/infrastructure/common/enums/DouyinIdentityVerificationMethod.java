package org.huangry.colorful.geo.infrastructure.common.enums;

/** 抖音扫码后的二次身份验证方式；由用户选择，不自动绕过验证。 */
public enum DouyinIdentityVerificationMethod {

    /** 在网页接收短信码，并由用户输入收到的验证码。 */
    RECEIVE_SMS,

    /** 按抖音页面提示从手机发送验证短信。 */
    SEND_SMS
}
