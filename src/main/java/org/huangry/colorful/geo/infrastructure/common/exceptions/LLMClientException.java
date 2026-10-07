package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 大模型基础设施调用失败异常。
 *
 * <p>用于向 application 层暴露可识别的配置、协议与远程调用失败语义。</p>
 *
 * @author huangry
 */
public class LLMClientException extends RuntimeException {

    public LLMClientException(String message) {
        super(message);
    }

    public LLMClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
