package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chat Completions 抽象交互：复用 HTTP、基础请求与引用解析，厂商参数由子策略补充。 */
public abstract class AbstractChatCompletionsApiStrategy implements LLMStrategy {
    /** 声明父类实际处理的交互格式，子类不重复维护。 */
    @Override
    public final LLMProtocolType protocolType() { return LLMProtocolType.OPENAI_CHAT_COMPLETIONS; }

    /**
     * 提交独立问题，读取原始响应以保留 SDK 未映射的引用注解。
     * @param request 当前问题
     * @param channel 渠道连接配置
     * @return 正文及接口明确返回的引用，无引用时为空列表
     */
    @Override
    public final LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 1. 校验必需配置，按渠道保留生成参数。
        if (channel.getBaseUrl() == null || channel.getBaseUrl().isBlank()
                || channel.getModelName() == null || channel.getModelName().isBlank()) {
            throw new LLMClientException("Chat Completions 渠道缺少 baseUrl 或 model");
        }
        Map<String, Object> body = buildRequestBody(request, channel);

        // 2. 密钥仅放入请求头，超时与证书策略沿用已有客户端工厂。
        String baseUrl = channel.getBaseUrl().replaceAll("/+$", "");
        JsonNode response = RestClient.builder()
                .requestFactory(LlmHttpClientFactory.createSpringRequestFactory(channel))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey().strip())
                .build().post().uri(baseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);

        // 3. 响应结构不合法明确报错，不能把空响应伪装成成功。
        if (response == null) throw new LLMClientException("Chat Completions 返回空响应");
        return parseResponse(response);
    }

    /**
     * 组装普通 Chat Completions 请求，子策略可添加搜索参数。
     * @param request 当前问题
     * @param channel 渠道配置
     * @return 协议基础请求体
     */
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", channel.getModelName());
        body.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        if (channel.getTemperature() != null) body.put("temperature", channel.getTemperature());
        if (channel.getMaxTokens() != null) body.put("max_tokens", channel.getMaxTokens());
        return body;
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
        LlmCitationParser.collect(message.path("annotations"), citations);
        return new LLMResponse(message.path("content").asText(), List.copyOf(citations.values()));
    }
}
