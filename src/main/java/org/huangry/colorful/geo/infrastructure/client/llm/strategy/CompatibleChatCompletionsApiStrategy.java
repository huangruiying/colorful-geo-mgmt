package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractChatCompletionsApiStrategy;
import org.springframework.stereotype.Component;

/**
 * 通用 Chat Completions 策略，供自定义网关或兼容接口选择。
 * <p>注册为 compatibleChatCompletionsApiStrategy；query 沿用模板请求和引用解析，
 * 不主动添加厂商搜索参数。接口返回结构化引用时仍会提取，不保证联网。</p>
 */
@Component
public class CompatibleChatCompletionsApiStrategy extends AbstractChatCompletionsApiStrategy {
}
