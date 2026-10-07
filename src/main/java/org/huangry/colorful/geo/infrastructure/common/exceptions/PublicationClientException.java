package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 内容投放通道处理失败异常。
 *
 * <p>用于表达策略缺失、投放参数非法或平台通道调用失败；不应包含账号凭据和完整请求内容。</p>
 *
 * @author huangry
 */
public class PublicationClientException extends RuntimeException {

    public PublicationClientException(String message) {
        super(message);
    }

    public PublicationClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
