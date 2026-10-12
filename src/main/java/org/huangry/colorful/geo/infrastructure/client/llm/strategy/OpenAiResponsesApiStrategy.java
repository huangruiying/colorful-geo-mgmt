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

/**
 * Responses 请求与结构化引用解析，不根据搜索标记限制引用提取。
 *
 * <p>OpenAI 供应商自动声明 web_search，其他供应商不假设支持该工具。
 * 从返回的 url_citation 注解提取来源信息；没有引用时返回空列表，不从正文猜测来源，也不负责核验来源正确性。</p>
 *
 * @author huangry
 */
@Component
public class OpenAiResponsesApiStrategy implements LLMStrategy {

    /** 注册 input/output 交互协议，不表示联网能力。 */
    @Override
    public LLMProtocolType llmProviderType() { return LLMProtocolType.OPENAI_RESPONSES; }

    /**
     * 调用 Responses API，按适配能力声明工具并解析回答及来源引用。
     *
     * @param request 通用查询请求
     * @param provider OpenAI 官方通道配置
     * @param apiKey 已解析的 OpenAI 密钥
     * @return 模型正文及接口返回的结构化引用；未返回引用时列表为空
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, LLMConnectionConfig provider, String apiKey) {
        validateProvider(provider);

        // 1. 搜索请求参数按供应商处理，不从接口协议推导联网能力。
        Map<String, Object> requestBody = buildRequestBody(request, provider);

        // 2. 保持 API Key 仅存在于请求头，日志和响应对象均不保留密钥。
        RestClient restClient = RestClient.builder()
                .requestFactory(LlmHttpClientFactory.createSpringRequestFactory(provider))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        JsonNode responseBody = restClient.post()
                .uri(normalizeBaseUrl(provider.getBaseUrl()) + "/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);
        if (responseBody == null) {
            throw new LLMClientException("OpenAI Responses API 返回空响应");
        }

        // 3. 只提取接口返回的结构化来源；来源是否准确仍需核验，缺少引用时保留空列表。
        return parseResponse(responseBody);
    }

    /** 组装 Responses 请求，OpenAI 供应商沿用自动搜索工具。 */
    Map<String, Object> buildRequestBody(LLMQueryRequest request, LLMConnectionConfig provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("input", List.of(Map.of(
            "role", "user",
            "content", List.of(Map.of("type", "input_text", "text", request.prompt())))));
        // OpenAI 的工具参数不能无条件发送给所有兼容供应商。
        if ("openai".equalsIgnoreCase(provider.getProviderCode())) {
            body.put("tools", List.of(Map.of("type", "web_search")));
        }
        body.put("store", false);
        return body;
    }

    /**
     * 解析 OpenAI Responses API 的正文和结构化来源引用。
     *
     * <p>只接受 output_text.annotations 内的 url_citation，正文中的 URL 不参与引用构建。</p>
     *
     * @param responseBody OpenAI 原始响应
     * @return 标准模型响应
     */
    LLMResponse parseResponse(JsonNode responseBody) {
        StringBuilder content = new StringBuilder();
        Map<String, LLMCitation> citationByUrl = new LinkedHashMap<>();
        for (JsonNode outputItem : responseBody.path("output")) {
            if (!"message".equals(outputItem.path("type").asText())) {
                continue;
            }
            for (JsonNode contentItem : outputItem.path("content")) {
                if (!"output_text".equals(contentItem.path("type").asText())) {
                    continue;
                }
                content.append(contentItem.path("text").asText());
                collectCitations(contentItem.path("annotations"), citationByUrl);
            }
        }
        return new LLMResponse(content.toString(), List.copyOf(citationByUrl.values()));
    }

    /**
     * 收集单段文本中的网页引用并按 URL 去重。
     *
     * @param annotations OpenAI 结构化注解
     * @param citationByUrl 已收集的引用索引
     */
    private void collectCitations(JsonNode annotations, Map<String, LLMCitation> citationByUrl) {
        for (JsonNode annotation : annotations) {
            if (!"url_citation".equals(annotation.path("type").asText())) {
                continue;
            }
            String url = annotation.path("url").asText();
            if (url.isBlank()) {
                continue;
            }
            citationByUrl.putIfAbsent(url, new LLMCitation(
                    annotation.path("title").asText(),
                    url,
                    annotation.has("start_index") ? annotation.path("start_index").asInt() : null,
                    annotation.has("end_index") ? annotation.path("end_index").asInt() : null,
                    annotation.path("text").asText()));
        }
    }

    /**
     * 校验 Responses 协议必需的连接配置。
     *
     * @param provider 当前启用供应商
     */
    private void validateProvider(LLMConnectionConfig provider) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                || provider.getModel() == null || provider.getModel().isBlank()) {
            throw new LLMClientException("OpenAI Responses 渠道缺少 baseUrl 或 model 配置");
        }
    }

    /**
     * 规范化 Responses API 的服务根地址。
     *
     * @param baseUrl 配置的服务根地址
     * @return 不以斜杠结尾的服务根地址
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
