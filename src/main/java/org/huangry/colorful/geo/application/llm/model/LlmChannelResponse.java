package org.huangry.colorful.geo.application.llm.model;

import lombok.Data;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import java.time.LocalDateTime;

/** 模型渠道接口响应；只展示配置和密钥保存状态，不包含明文密钥。 */
@Data
public class LlmChannelResponse {
    /** 数据库生成的渠道标识。 */
    private Long id;
    /** 唯一渠道编码，创建后不可修改。 */
    private String channelCode;
    /** 页面展示名称。 */
    private String channelName;
    /** 供应商标识。 */
    private String providerCode;
    /** 调用协议。 */
    private LLMProviderType protocolType;
    /** 接口根地址。 */
    private String baseUrl;
    /** 模型标识。 */
    private String modelName;
    /** 是否已保存密钥；已有密钥不回传页面。 */
    private Boolean keyConfigured;
    /** 生成随机性。 */
    private Double temperature;
    /** 输出 token 上限。 */
    private Integer maxTokens;
    /** 超时秒数。 */
    private Integer timeoutSeconds;
    /** 是否校验证书。 */
    private Boolean sslVerify;
    /** 展示协议支持的搜索能力，配置唯一来源为协议枚举。 */
    public boolean isWebSearchSupported() {
        return protocolType != null && protocolType.isWebSearchSupported();
    }
    /** 默认选择优先级，越大越优先。 */
    private Integer score;
    /** 是否启用。 */
    private Boolean enabled;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
