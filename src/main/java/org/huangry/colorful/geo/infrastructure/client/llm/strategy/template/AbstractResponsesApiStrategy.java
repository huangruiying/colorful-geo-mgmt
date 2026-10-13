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
import jakarta.annotation.Resource;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responses 协议模板，query 负责独立问题请求、HTTP 调用和回答引用解析。
 *
 * <p>基础请求不声明搜索工具，厂商搜索参数由子策略补充。
 * 从返回的 url_citation 注解提取来源信息；没有引用时返回空列表，不从正文猜测来源，也不负责核验来源正确性。</p>
 *
 * @author huangry
 */
public abstract class AbstractResponsesApiStrategy implements LLMStrategy {
    /** 提前注册的模型 HTTP 客户端，超时及证书策略统一配置。 */
    @Resource(name = "llmRestTemplateWithTimeout")
    private RestTemplate llmRestTemplate;
    /** 声明父类实际处理的交互格式，子类不重复维护。 */
    @Override
    public final LLMProtocolType protocolType() { return LLMProtocolType.OPENAI_RESPONSES; }

    /**
     * 调用 Responses API，按适配能力声明工具并解析回答及来源引用。
     *
     * @param request 通用查询请求
     * @param channel 已选中的渠道配置，可来自官方接口或兼容网关
     * @return 模型正文及接口返回的结构化引用；未返回引用时列表为空
     */
    @Override
    public final LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        validateProvider(channel);

        // 1. 搜索请求参数按供应商处理，不从接口协议推导联网能力。
        Map<String, Object> requestBody = buildRequestBody(request, channel);

        // 2. 保持 API Key 仅存在于请求头，日志和响应对象均不保留密钥。
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(channel.getApiKey().strip());
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode responseBody = llmRestTemplate
                .postForObject(normalizeBaseUrl(channel.getBaseUrl()) + "/responses",
                        new HttpEntity<>(requestBody, headers), JsonNode.class);
        if (responseBody == null) {
            throw new LLMClientException("OpenAI Responses API 返回空响应");
        }

        // 3. 只提取接口返回的结构化来源；来源是否准确仍需核验，缺少引用时保留空列表。
        return parseResponse(responseBody);
    }

    /**
     * 组装单轮 input，并请求不保存会话，厂商工具参数由子策略添加。
     * <p>当前基础请求不发送渠道的 temperature 和 maxTokens。</p>
     * @param request 本次独立问题
     * @param channel 已选中的渠道配置
     * @return Responses 基础请求体
     */
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", channel.getModelName());
        body.put("input", List.of(Map.of(
            "role", "user",
            "content", List.of(Map.of("type", "input_text", "text", request.prompt())))));
        body.put("store", false);
        return body;
    }

    /**
     * 解析 OpenAI Responses API 的正文和结构化来源引用。
     *
     * <p>只接受 output_text.annotations 内的 url_citation，正文中的 URL 不参与引用构建。</p>
     *
     * @param responseBody Responses 原始响应
     * @return 标准模型响应
     */
    LLMResponse parseResponse(JsonNode responseBody) {
        // 失败或被截断的回答不能当成完整诊断结果，错误不透传远端原文。
        String status = responseBody.path("status").asText();
        if (responseBody.hasNonNull("error") || "failed".equals(status) || "incomplete".equals(status)) {
            throw new LLMClientException("Responses 返回失败或不完整回答");
        }
        // 按返回顺序拼接消息文本；工具调用、检索结果不作为回答正文。
        StringBuilder content = new StringBuilder();
        boolean hasText = false;
        Map<String, LLMCitation> citationByUrl = new LinkedHashMap<>();
        for (JsonNode outputItem : responseBody.path("output")) {
            if (!"message".equals(outputItem.path("type").asText())) {
                continue;
            }
            for (JsonNode contentItem : outputItem.path("content")) {
                if (!"output_text".equals(contentItem.path("type").asText())) {
                    continue;
                }
                if (!contentItem.path("text").isTextual()) continue;
                hasText = true;
                content.append(contentItem.path("text").asText());
                // 引用只从当前文本注解提取，搜索召回来源不计入回答引用。
                LlmCitationParser.collect(contentItem.path("annotations"), citationByUrl);
            }
        }
        // 只有工具调用而没有文本时明确失败，不伪装成成功的空答案。
        if (!hasText) throw new LLMClientException("Responses 未返回文本回答");
        return new LLMResponse(content.toString(), List.copyOf(citationByUrl.values()));
    }

    /**
     * 校验 Responses 协议必需的连接配置。
     *
     * @param channel 当前渠道的连接地址和模型配置
     */
    private void validateProvider(GeoLlmChannelEntity channel) {
        if (channel.getBaseUrl() == null || channel.getBaseUrl().isBlank()
                || channel.getModelName() == null || channel.getModelName().isBlank()) {
            throw new LLMClientException("OpenAI Responses 渠道缺少 baseUrl 或 model 配置");
        }
    }

    /**
     * 规范化 Responses API 的服务根地址。
     *
     * @param baseUrl 配置的服务根地址
     * @return 去除一个末尾斜杠后的地址，其余内容不变
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
