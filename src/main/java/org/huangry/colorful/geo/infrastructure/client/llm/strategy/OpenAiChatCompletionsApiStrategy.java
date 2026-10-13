package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractChatCompletionsApiStrategy;
import org.springframework.stereotype.Component;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import java.util.Map;

/**
 * OpenAI Chat Completions 策略，注册为 openAiChatCompletionsApiStrategy。
 * <p>buildRequestBody 仅对 gpt-5-search-api 添加搜索选项；其他模型沿用普通请求。
 * query 的 HTTP 调用与引用解析由模板负责，不按协议名称推断所有模型都能联网。</p>
 */
@Component
public class OpenAiChatCompletionsApiStrategy extends AbstractChatCompletionsApiStrategy {

    /**
     * 为已适配的搜索模型补充参数，其他模型保留基础问答配置。
     * @param request 本次独立问题
     * @param channel 已选中的渠道配置
     * @return 按模型能力调整后的 Chat 请求体
     */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 先使用模板生成独立问题与渠道参数。
        Map<String, Object> body = super.buildRequestBody(request, channel);
        // 搜索专用模型使用搜索选项，移除该适配不发送的普通生成参数。
        if ("gpt-5-search-api".equals(channel.getModelName())) {
            body.remove("temperature");
            body.remove("max_tokens");
            body.put("web_search_options", Map.of());
        }
        return body;
    }
}
