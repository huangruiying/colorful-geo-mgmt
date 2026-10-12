package org.huangry.colorful.geo.infrastructure.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 大模型供应商协议类型。
 *
 * <p>用于供应商配置绑定及调用策略选择；多个供应商可以使用同一种协议类型。</p>
 *
 * @author huangry
 */
@Getter
@RequiredArgsConstructor
public enum LLMProviderType {

    /** 不发起远程请求的本地模拟通道。 */
    MOCK("本地模拟", false),

    /** OpenAI标准版, 兼容 OpenAI Chat Completions 协议的远程通道。 */
    OPENAI_STANDARD("OpenAI 兼容问答（Chat Completions）", false),

    /** Responses 协议可按请求启用搜索工具。 */
    OPENAI_WEB_SEARCH("OpenAI Responses（支持联网搜索）", true);

    /** 页面展示及协议说明。 */
    private final String description;

    /** 当前策略是否已实现搜索工具协议，不保证具体模型可用或实际搜索。 */
    private final boolean webSearchSupported;
}
