package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.Nonnull;
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
import lombok.extern.slf4j.Slf4j;

/**
 * 发送 Chat Completions 请求并解析正文、引用和元数据。
 * 复用当前业务使用的基础协议；子策略调整请求参数，连接资源使用共用 HTTP 客户端。
 */
@Slf4j
public abstract class AbstractChatCompletionsApiStrategy implements LLMStrategy {
    /**
     * 声明当前协议格式，不据此推断模型或搜索能力。
     * @return 当前接口协议
     */
    @Override
    public LLMProtocolType protocolType() {
        return LLMProtocolType.OPENAI_CHAT_COMPLETIONS;
    }

    /** 提前注册的模型 HTTP 客户端，超时及证书策略统一配置。 */
    @Resource(name = "llmRestTemplateWithTimeout")
    private RestTemplate llmRestTemplate;
    /**
     * 提交独立问题并直接读取 JSON，保留回答中的结构化引用注解。
     * @param request 当前独立问题
     * @param channel 渠道连接配置
     * @return 正文、结构化引用及厂商元数据，无引用时为空列表
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 1. 校验必需配置，再由具体策略组装请求参数。
        if (channel.getBaseUrl() == null || channel.getBaseUrl().isBlank()
                || channel.getModelName() == null || channel.getModelName().isBlank()) {
            throw new LLMClientException("Chat Completions 渠道缺少 baseUrl 或 model");
        }

        Map<String, Object> body = buildRequestBody(request, channel);
        log.info("模型请求 channel={}, protocol=chatCompletions, model={}, stream=false, search={}, maxTokens={}, temperature={}, promptChars={}",
                channel.getChannelCode(), channel.getModelName(), body.containsKey("web_search_options"),
                body.get("max_tokens"), body.get("temperature"), request.prompt().length());

        // 2. 密钥仅放入请求头，超时与证书策略沿用统一 HTTP 客户端。
        String baseUrl = channel.getBaseUrl().replaceAll("/+$", "");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(channel.getApiKey().strip());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response = llmRestTemplate
                .postForObject(baseUrl + "/chat/completions", new HttpEntity<>(body, headers), String.class);

        // 3. 响应结构不合法明确报错，不能把空响应伪装成成功。
        if (response == null || response.isBlank() || "null".equals(response.strip())) {
            throw new LLMClientException("Chat Completions 返回空响应");
        }
        return parseResponse(JSON.parseObject(response));
    }

    /**
     * 组装普通 Chat Completions 请求，只发送基础文本问答参数。
     * @param request 当前问题
     * @param channel 渠道配置
     * @return 当前策略的请求体
     */
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 组装独立问题，生成参数仅在用户填写时发送
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", channel.getModelName());
        body.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
        if (channel.getTemperature() != null) {
            body.put("temperature", channel.getTemperature());
        }
        if (channel.getMaxTokens() != null) {
            body.put("max_tokens", channel.getMaxTokens());
        }
        return body;
    }

    /**
     * 解析首个回答和 OpenAI 标准 annotations，不合并不同候选回答的引用。
     * @param response 原始响应
     * @return 首个回答、按链接去重的引用及实际响应元数据
     */
    LLMResponse parseResponse(JSONObject response) {
        // 1. 只采用首个候选的文本，缺失或非文本明确失败，避免合并多个诊断结果
        JSONArray choices = response.get("choices") instanceof JSONArray array ? array : null;
        JSONObject first = choices == null || choices.isEmpty() || !(choices.get(0) instanceof JSONObject)
                ? null : choices.getJSONObject(0);
        JSONObject message = first != null && first.get("message") instanceof JSONObject object ? object : null;
        if (message == null || !(message.get("content") instanceof String)) {
            throw new LLMClientException("Chat Completions 未返回文本回答");
        }
        // 2. 引用仅取当前回答的注解，正文 URL 不作为引用来源
        Map<String, LLMCitation> citations = new LinkedHashMap<>();
        LlmCitationParser.collect(message.get("annotations"), citations);
        String reason = first.get("finish_reason") instanceof String value ? value : null;
        // 3. 元数据保留厂商原始字段及显式空值，结果对象不负责 JSON 解析
        return assembleLLMResponse(response, message, citations, reason);
    }

    /**
     * 将本次有效回答及厂商元数据组装为统一结果，保留原始 JSON 的显式空值。
     * @param response 厂商原始响应对象
     * @param message 首个候选的消息对象
     * @param citations 按 URL 去重的结构化引用
     * @param reason 厂商结束原因或状态，未提供时为空
     * @return 正文、引用及实际响应元数据
     */
    @Nonnull
    private static LLMResponse assembleLLMResponse(JSONObject response, JSONObject message, Map<String, LLMCitation> citations, String reason) {
        LLMResponse result = new LLMResponse(message.getString("content"), List.copyOf(citations.values()));
        result.setModelName(response.get("model") instanceof String value ? value : null);
        result.setUsageInfo(response.get("usage") == null ? null : JSON.toJSONString(response.get("usage"), SerializerFeature.WriteMapNullValue));
        result.setFinishReason(reason);
        result.setRawResponse(JSON.toJSONString(response, SerializerFeature.WriteMapNullValue));
        return result;
    }
}
