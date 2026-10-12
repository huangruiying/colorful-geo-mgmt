package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    /** 标准 Chat Completions 问答协议。 */
    @Override
    public LLMProviderType llmProviderType() { return LLMProviderType.OPENAI_STANDARD; }

    /**
     * 通过 LangChain4j 调用标准 Chat Completions 协议。
     *
     * @param request 通用查询请求
     * @param provider 供应商配置
     * @param apiKey 已解析的访问密钥
     * @return 不带网页引用的标准响应
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, LLMConnectionConfig provider, String apiKey) {
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

        // 2. 每次只提交当前问题，不传入历史回答。
        ChatResponse response = modelBuilder.build().chat(UserMessage.from(request.prompt()));
        return LLMResponse.withoutCitations(response.aiMessage().text());
    }

    /**
     * 校验 OpenAI-compatible 调用必需的连接配置。
     *
     * @param provider 当前启用供应商
     */
    private void validateProvider(LLMConnectionConfig provider) {
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
