package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 发布动作已经发起，但平台最终结果尚未确认。
 *
 * <p>调用方应保留待核对状态，禁止自动重试；此异常不表示平台一定发布失败。</p>
 *
 * @author huangry
 */
public class PublicationOutcomeUnknownException extends PublicationClientException {

    /**
     * 发布已触发但尚无法确认平台终态。
     * @param message 对调用方可展示的核对提示
     */
    public PublicationOutcomeUnknownException(String message) {
        super(message);
    }

    /**
     * 保留导致结果不确定的原始异常，供受控排障使用。
     * @param message 对调用方可展示的核对提示
     * @param cause 原始通道异常
     */
    public PublicationOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
