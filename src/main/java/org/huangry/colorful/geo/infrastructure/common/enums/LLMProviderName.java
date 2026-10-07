package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 内置大模型供应商配置名称。
 *
 * <p>配置仍允许新增名称，枚举仅用于代码中的内置默认值，避免限制后续自定义网关。</p>
 *
 * @author huangry
 */
public enum LLMProviderName {

    /** 本地模拟通道。 */
    MOCK("mock"),
    /** OpenAI 官方通道。 */
    OPENAI("openai"),
    /** DeepSeek 通道。 */
    DEEPSEEK("deepseek"),
    /** 通义千问通道。 */
    QWEN("qwen"),
    /** Moonshot Kimi 通道。 */
    KIMI("kimi"),
    /** 用户自定义 OpenAI-compatible 网关通道。 */
    CUSTOM("custom");

    /** 供应商配置键，与 providers 下的配置名称对应，不是模型名称或协议类型。 */
    private final String configurationName;

    /**
     * 绑定内置供应商对应的配置名称。
     *
     * @param configurationName providers 下的配置键
     */
    LLMProviderName(String configurationName) {
        this.configurationName = configurationName;
    }

    /**
     * 获取 application.yml 中使用的 provider 名称。
     *
     * @return 配置名称
     */
    public String configurationName() {
        return configurationName;
    }
}
