package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMMessage;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * OpenAI Chat Completions 兼容策略。
 *
 * <p>承载 OpenAI、DeepSeek、Qwen、Kimi 与自定义网关的通用文本查询；不伪造网页引用。</p>
 *
 * @author huangry
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmStrategy implements LLMStrategy {

    @Override
    public boolean supports(LLMProviderProperties.Provider provider, boolean webSearchEnabled) {
        return provider.getType() == LLMProviderType.OPENAI_COMPATIBLE
                && (!webSearchEnabled || !provider.isWebSearchSupported());
    }

    @Override
    public int priority() {
        return 30;
    }

    /**
     * 通过 LangChain4j 调用标准 Chat Completions 协议。
     *
     * @param request 通用查询请求
     * @param provider 供应商配置
     * @param apiKey 已解析的访问密钥
     * @return 不带网页引用的标准响应
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, LLMProviderProperties.Provider provider, String apiKey) {
        validateProvider(provider);

        // 1. 依据供应商连接参数构造独立模型实例，防止不同 provider 间配置串用。
        OpenAiChatModel.OpenAiChatModelBuilder modelBuilder = OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(provider.getBaseUrl()))
                .apiKey(apiKey)
                .modelName(provider.getModel())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                .timeout(Duration.ofSeconds(provider.getTimeoutSeconds()));
        if (!provider.isSslVerify()) {
            modelBuilder.httpClientBuilder(LlmHttpClientFactory.createLangChain4jClientBuilder(provider));
        }

        // 2. 映射通用消息角色，并调用 OpenAI-compatible 协议。
        log.info("请求大模型 request param {}", request.messages());
        ChatResponse response = modelBuilder.build().chat(toChatMessages(request.messages()));
        log.info("请求大模型 response result {}", response.toString());
        return LLMResponse.withoutCitations(response.aiMessage().text());
    }

    /**
     * 将基础设施层的通用消息转换为 LangChain4j 消息。
     *
     * @param messages 调用方提供的标准会话消息
     * @return LangChain4j 可识别的消息列表
     */
    private List<ChatMessage> toChatMessages(List<LLMMessage> messages) {
        return messages.stream().map(this::toChatMessage).toList();
    }

    /**
     * 按标准角色创建协议消息。
     *
     * <p>角色映射集中在此处，避免业务层依赖 LangChain4j 的具体消息类型。</p>
     *
     * @param message 通用消息
     * @return 目标协议消息
     */
    private ChatMessage toChatMessage(LLMMessage message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> AiMessage.from(message.content());
        };
    }

    /**
     * 校验 OpenAI-compatible 调用必需的连接配置。
     *
     * @param provider 当前启用供应商
     */
    private void validateProvider(LLMProviderProperties.Provider provider) {
        if (isBlank(provider.getBaseUrl()) || isBlank(provider.getModel())) {
            throw new LLMClientException("OpenAI-compatible 供应商缺少 baseUrl 或 model 配置");
        }
    }

    /**
     * 规范化服务根地址，避免 SDK 拼接接口路径时产生双斜杠。
     *
     * @param baseUrl 配置的服务根地址
     * @return 不以斜杠结尾的服务根地址
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 判断配置项是否缺失。
     *
     * @param value 待判断配置值
     * @return 是否为空白值
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
