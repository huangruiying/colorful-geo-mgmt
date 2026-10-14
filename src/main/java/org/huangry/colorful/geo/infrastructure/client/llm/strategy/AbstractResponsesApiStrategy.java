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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 发送 Responses 请求并校验、解析最终回答和引用。
 * 复用当前业务使用的基础协议；子策略调整请求参数，连接资源使用共用 HTTP 客户端。
 */
@Slf4j
public abstract class AbstractResponsesApiStrategy implements LLMStrategy {
    /**
     * 声明当前协议格式，不据此推断模型或搜索能力。
     * @return 当前接口协议
     */
    @Override
    public LLMProtocolType protocolType() {
        return LLMProtocolType.OPENAI_RESPONSES;
    }

    /** 提前注册的模型 HTTP 客户端，超时及证书策略统一配置。 */
    @Resource(name = "llmRestTemplateWithTimeout")
    private RestTemplate llmRestTemplate;
    /**
     * 组装并发送 Responses 请求，按策略选择完整 JSON 或 SSE 完成事件解析。
     *
     * @param request 当前独立问题
     * @param channel 已选中的渠道配置，可来自官方接口或兼容网关
     * @return 正文、结构化引用及实际响应元数据；未返回引用时列表为空
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 1. 校验接口必需配置，缺失时不发起远程调用
        validateProvider(channel);

        Map<String, Object> requestBody = buildRequestBody(request, channel);

        // 2. 密钥仅放在请求头；流式请求在连接内持续读取，普通请求读取完整 JSON。
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(channel.getApiKey().strip());
        headers.setContentType(MediaType.APPLICATION_JSON);
        log.info("模型请求 channel={}, protocol=responses, model={}, stream={}, tools={}, toolChoice={}, maxOutputTokens={}, temperature={}, promptChars={}",
                channel.getChannelCode(), channel.getModelName(), Boolean.TRUE.equals(requestBody.get("stream")),
                requestBody.get("tools"), requestBody.get("tool_choice"), requestBody.get("max_output_tokens"),
                requestBody.get("temperature"), request.prompt().length());

        // 3. 确定 URL
        String requestURL = normalizeBaseUrl(channel.getBaseUrl()) + "/responses";

        // 仅策略显式开启 stream=true 时读取 SSE；Boolean.TRUE.equals 可安全处理未填写的 null。
        // 联网搜索可能较慢，持续读取进度事件可避免一直等最终 JSON；首响应及读取空闲超时仍生效。
        if (Boolean.TRUE.equals(requestBody.get("stream"))) {
            // Accept 声明期望接收 SSE；请求体仍是上面 Content-Type 指定的 JSON。
            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
            // execute 分别提供请求写入、响应读取回调，避免用 String 转换器先缓冲整个事件流。
            // 响应读取是同步的：回调内读到完成事件并组装结果后才返回，页面不会收到逐字推送。
            // 必须在回调内消费响应流，因为 execute 结束时 RestTemplate 会关闭响应并释放连接资源。
            log.info("Accept SSE Request URL: {}", requestURL);
            return llmRestTemplate.execute(requestURL, HttpMethod.POST,
                    outgoing -> {
                        // 请求回调：携带认证和媒体类型，以 UTF-8 JSON 字节写入当前策略的完整请求体。
                        outgoing.getHeaders().putAll(headers);
                        outgoing.getBody().write(JSON.toJSONBytes(requestBody));
                        // 响应回调：逐帧读取，只有完成事件中的完整回答可返回；失败或断流继续抛错。
                    }, this::readStreamResponse);
        }
        String responseBody = llmRestTemplate
                .postForObject(requestURL, new HttpEntity<>(requestBody, headers), String.class);
        if (responseBody == null || responseBody.isBlank() || "null".equals(responseBody.strip())) {
            throw new LLMClientException("OpenAI Responses API 返回空响应");
        }

        // 3. 只提取接口返回的结构化来源；来源是否准确仍需核验，缺少引用时保留空列表。
        return parseResponse(JSON.parseObject(responseBody));
    }

    /**
     * 在响应连接关闭前读取 SSE，只用完成事件中的完整响应组装结果。
     * @param response 当前 HTTP 响应，资源由 RestTemplate 统一关闭
     * @return 完整模型回答，断流、失败或不完整事件不会产生成功结果
     * @throws IOException 读取连接失败或超时
     */
    private LLMResponse readStreamResponse(ClientHttpResponse response) throws IOException {
        // 1. 兼容明确返回 JSON 的网关，仍按原有完整响应规则校验。
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return parseResponse(JSON.parseObject(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        }
        // 2. 按 SSE 空行分帧，多行 data 合并；忽略心跳及非 data 字段，不拼接增量正文。
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                LLMResponse result = parseStreamEvent(data.toString());
                data.setLength(0);
                if (result != null) {
                    return result;
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        // 3. EOF 前最后一帧可以没有空行；没有完成事件则拒绝保存部分回答。
        LLMResponse result = parseStreamEvent(data.toString());
        if (result == null) {
            throw new LLMClientException("Responses 流在完整回答返回前中断");
        }
        log.info("模型请求完成，响应结果: {}",JSON.toJSONString(result));
        return result;
    }

    /**
     * 识别最终完成事件，其他进度事件不作为回答，失败事件立即终止调用。
     * @param data 单个 SSE 事件的数据，不记录远端文本
     * @return 完整回答；进度事件返回 null
     */
    private LLMResponse parseStreamEvent(String data) {
        if (data.isBlank()) {
            return null;
        }
        if ("[DONE]".equals(data.strip())) {
            throw new LLMClientException("Responses 流未返回完成事件");
        }
        JSONObject event = JSON.parseObject(data);
        String type = event.getString("type");
        if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
            throw new LLMClientException("Responses 流返回失败或不完整回答");
        }
        if (!"response.completed".equals(type)) {
            return null;
        }
        if (!(event.get("response") instanceof JSONObject completed)
                || !"completed".equals(completed.getString("status"))) {
            throw new LLMClientException("Responses 完成事件缺少有效响应");
        }
        return parseResponse(completed);
    }

    /**
     * 组装单轮 input，并请求不保存会话，工具参数由本策略显式决定。
     * <p>输出上限使用 max_output_tokens，包含接口计入的推理 token；
     * 随机性仅在用户填写时发送，由实际模型校验是否接受。</p>
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
        // 输出长度采用 Responses 字段名，不发送 Chat 的 max_tokens。
        if (channel.getMaxTokens() != null) {
            body.put("max_output_tokens", channel.getMaxTokens());
        }
        // 留空采用供应商行为，填写则原值发送，不按模型名称猜测或静默过滤。
        if (channel.getTemperature() != null) {
            body.put("temperature", channel.getTemperature());
        }
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
    LLMResponse parseResponse(JSONObject responseBody) {
        // 1. 失败或被截断的回答不能当成完整诊断结果，错误不透传远端原文
        String status = responseBody.getString("status");
        if (responseBody.get("error") != null || "failed".equals(status) || "incomplete".equals(status)) {
            throw new LLMClientException("Responses 返回失败或不完整回答");
        }
        // 2. 按返回顺序拼接消息文本；工具调用、检索结果不作为回答正文
        StringBuilder content = new StringBuilder();
        boolean hasText = false;
        Map<String, LLMCitation> citationByUrl = new LinkedHashMap<>();
        JSONArray output = responseBody.get("output") instanceof JSONArray array ? array : null;
        for (Object value : output == null ? List.of() : output) {
            if (!(value instanceof JSONObject outputItem) || !"message".equals(outputItem.getString("type"))) {
                continue;
            }
            JSONArray items = outputItem.get("content") instanceof JSONArray array ? array : null;
            for (Object item : items == null ? List.of() : items) {
                if (!(item instanceof JSONObject contentItem) || !"output_text".equals(contentItem.getString("type"))
                        || !(contentItem.get("text") instanceof String)) {
                    continue;
                }
                hasText = true;
                content.append(contentItem.getString("text"));
                // 引用只从当前文本注解提取，搜索召回来源不计入回答引用。
                LlmCitationParser.collect(contentItem.get("annotations"), citationByUrl);
            }
        }
        // 3. 只有工具调用而没有文本时明确失败，成功时保留原始元数据和显式空值
        if (!hasText) {
            throw new LLMClientException("Responses 未返回文本回答");
        }
        String reason = responseBody.get("status") instanceof String value ? value : null;
        // 直接填充网络响应字段，结果对象不负责 JSON 解析。
        return assembleLLMResponse(responseBody, content, citationByUrl, reason);
    }

    /**
     * 将本次有效回答及厂商元数据组装为统一结果，保留原始 JSON 的显式空值。
     * @param responseBody 厂商原始响应对象
     * @param content 已拼接的最终文本
     * @param citationByUrl 按 URL 去重的结构化引用
     * @param reason 厂商结束原因或状态，未提供时为空
     * @return 正文、引用及实际响应元数据
     */
    @Nonnull
    private static LLMResponse assembleLLMResponse(JSONObject responseBody, StringBuilder content, Map<String, LLMCitation> citationByUrl, String reason) {
        LLMResponse result = new LLMResponse(content.toString(), List.copyOf(citationByUrl.values()));
        result.setModelName(responseBody.get("model") instanceof String value ? value : null);
        result.setUsageInfo(responseBody.get("usage") == null ? null : JSON.toJSONString(responseBody.get("usage"), SerializerFeature.WriteMapNullValue));
        result.setFinishReason(reason);
        result.setRawResponse(JSON.toJSONString(responseBody, SerializerFeature.WriteMapNullValue));
        return result;
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
