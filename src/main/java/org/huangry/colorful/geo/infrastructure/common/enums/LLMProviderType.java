package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 大模型供应商协议类型。
 *
 * <p>用于供应商配置绑定及调用策略选择；多个供应商可以使用同一种协议类型。</p>
 *
 * @author huangry
 */
public enum LLMProviderType {

    /** 不发起远程请求的本地模拟通道。 */
    MOCK,

    /** 兼容 OpenAI Chat Completions 协议的远程通道。 */
    OPENAI_COMPATIBLE
}
