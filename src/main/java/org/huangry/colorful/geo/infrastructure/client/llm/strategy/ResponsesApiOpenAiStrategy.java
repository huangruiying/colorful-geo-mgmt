package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;

/**
 * OpenAI Responses 联网问答策略。
 * 声明搜索工具并由协议父类执行；工具是否可用由实际模型和账号权限决定。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiOpenAiStrategy extends AbstractResponsesApiStrategy {
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
        // 厂商搜索参数由本策略维护，不向通用策略注入工具。
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("tool_choice", "required");
        return body;
    }
}
