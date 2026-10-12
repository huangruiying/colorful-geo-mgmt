package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chat Completions 调用与结构化引用解析，不从正文链接推断引用或联网能力。 */
@Component
public class OpenAiChatCompletionsApiStrategy implements LLMStrategy {

    /** 注册 messages/choices 交互协议。 */
    @Override
    public LLMProtocolType llmProviderType() {
        return LLMProtocolType.OPENAI_CHAT_COMPLETIONS;
    }

    /**
     * 提交独立问题，读取原始响应以保留 SDK 未映射的引用注解。
     * @param request 当前问题
     * @param provider 渠道连接配置
     * @param apiKey 服务端访问密钥
     * @return 正文及接口明确返回的引用，无引用时为空列表
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, LLMConnectionConfig provider, String apiKey) {
        // 1. 校验必需配置，按渠道保留生成参数。
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                || provider.getModel() == null || provider.getModel().isBlank()) {
            throw new LLMClientException("Chat Completions 渠道缺少 baseUrl 或 model");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        if (provider.getTemperature() != null) body.put("temperature", provider.getTemperature());
        if (provider.getMaxTokens() != null) body.put("max_tokens", provider.getMaxTokens());

        // 2. 密钥仅放入请求头，超时与证书策略沿用已有客户端工厂。
        String baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        JsonNode response = RestClient.builder()
                .requestFactory(LlmHttpClientFactory.createSpringRequestFactory(provider))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build().post().uri(baseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);

        // 3. 响应结构不合法明确报错，不能把空响应伪装成成功。
        if (response == null) throw new LLMClientException("Chat Completions 返回空响应");
        return parseResponse(response);
    }

    /**
     * 解析首个回答和 OpenAI 标准 annotations，不合并不同候选回答的引用。
     * @param response 原始响应
     * @return 首个回答及按链接去重的结构化引用
     */
    LLMResponse parseResponse(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (!message.path("content").isTextual()) {
            throw new LLMClientException("Chat Completions 未返回文本回答");
        }
        Map<String, LLMCitation> citations = new LinkedHashMap<>();
        for (JsonNode annotation : message.path("annotations")) {
            if (!"url_citation".equals(annotation.path("type").asText())) continue;
            JsonNode citation = annotation.path("url_citation");
            String url = citation.path("url").asText();
            if (url.isBlank()) continue;
            citations.putIfAbsent(url, new LLMCitation(citation.path("title").asText(), url,
                    citation.hasNonNull("start_index") ? citation.path("start_index").asInt() : null,
                    citation.hasNonNull("end_index") ? citation.path("end_index").asInt() : null,
                    citation.path("text").asText()));
        }
        return new LLMResponse(message.path("content").asText(), List.copyOf(citations.values()));
    }
}
