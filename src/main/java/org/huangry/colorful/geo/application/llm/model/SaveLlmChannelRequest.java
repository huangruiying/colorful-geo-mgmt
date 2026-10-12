package org.huangry.colorful.geo.application.llm.model;

import lombok.Data;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

/** 模型渠道保存请求；接收连接、密钥与优先级，密钥仅允许输入且不打印。 */
@Data
public class SaveLlmChannelRequest {
    /** 唯一渠道编码，创建后不可修改。 */
    private String channelCode;
    /** 页面展示名称。 */
    private String channelName;
    /** 供应商标识。 */
    private String providerCode;
    /** 调用协议。 */
    private LLMProtocolType protocolType;
    /** 接口根地址。 */
    private String baseUrl;
    /** 模型标识。 */
    private String modelName;
    /** 新密钥；编辑时空值表示保留，不通过响应回传。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @lombok.ToString.Exclude
    private String apiKey;
    /** 生成随机性。 */
    private Double temperature;
    /** 输出 token 上限。 */
    private Integer maxTokens;
    /** 超时秒数。 */
    private Integer timeoutSeconds;
    /** 是否校验证书。 */
    private Boolean sslVerify;
    /** 默认选择优先级，越大越优先。 */
    private Integer score;
    /** 是否启用。 */
    private Boolean enabled;
}
