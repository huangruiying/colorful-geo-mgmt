package org.huangry.colorful.geo.infrastructure.client.llm.model;

import lombok.Getter;
import lombok.Setter;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;

/** 数据库渠道的调用快照；供协议策略使用，不绑定配置文件或携带密钥。 */
@Getter
@Setter
public class LLMConnectionConfig {
    /** 调用协议类型。 */
    private LLMProviderType type;
    /** 接口根地址。 */
    private String baseUrl;
    /** 模型标识。 */
    private String model;
    /** 生成随机性。 */
    private Double temperature;
    /** 输出上限。 */
    private Integer maxTokens;
    /** 超时秒数。 */
    private Integer timeoutSeconds;
    /** 是否校验证书。 */
    private boolean sslVerify;
}
