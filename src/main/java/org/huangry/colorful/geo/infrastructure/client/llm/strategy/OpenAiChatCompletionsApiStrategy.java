package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractChatCompletionsApiStrategy;
import org.springframework.stereotype.Component;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import java.util.Map;

/** OpenAI Chat Completions（搜索模型联网）适配，复用父类交互及解析，由 Spring 注册；不选择渠道。 */
@Component
public class OpenAiChatCompletionsApiStrategy extends AbstractChatCompletionsApiStrategy {

    /** 搜索专用模型声明搜索参数；普通 OpenAI 模型沿用基础问答。 */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = super.buildRequestBody(request, channel);
        if ("gpt-5-search-api".equals(channel.getModelName())) {
            body.remove("temperature");
            body.remove("max_tokens");
            body.put("web_search_options", Map.of());
        }
        return body;
    }
}
