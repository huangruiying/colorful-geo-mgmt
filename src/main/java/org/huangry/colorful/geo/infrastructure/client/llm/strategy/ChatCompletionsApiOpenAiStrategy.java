package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * OpenAI Chat Completions 问答策略。
 * 维护厂商请求参数并由协议父类执行；仅为已适配的 gpt-5-search-api 添加搜索选项。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ChatCompletionsApiOpenAiStrategy extends AbstractChatCompletionsApiStrategy {
    /**
     * 声明 OpenAI 平台的 API Key 管理页，供配置页面引导获取密钥。
     * @return OpenAI 平台 API Key 管理页地址
     */
    @Override
    public String apiKeyConsoleUrl() {
        return "https://platform.openai.com/api-keys";
    }

    /**
     * 在协议基础请求上维护当前策略的参数。
     * @param request 当前问题
     * @param channel 渠道配置
     * @return 本次调用的完整请求体
     */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = super.buildRequestBody(request, channel);
        // 搜索专用模型不发送普通生成参数，保持精确模型匹配。
        if ("gpt-5-search-api".equals(channel.getModelName())) {
            body.remove("temperature");
            body.remove("max_tokens");
            body.put("web_search_options", Map.of());
        }
        return body;
    }
}
