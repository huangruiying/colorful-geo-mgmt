package org.huangry.colorful.geo.infrastructure.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 大模型接口协议，只决定请求和响应格式。 */
@Getter
@RequiredArgsConstructor
public enum LLMProtocolType {

    /** 不调用远端，用于本地模拟。 */
    MOCK("本地模拟"),

    /** 使用 messages 请求和 choices 响应的兼容接口。 */
    OPENAI_CHAT_COMPLETIONS("Chat Completions"),

    /** 使用 input 请求和 output 响应的兼容接口。 */
    OPENAI_RESPONSES("Responses");

    /** 页面展示的协议名称。 */
    private final String description;
}

