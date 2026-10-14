package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;

/**
 * 豆包 Responses 联网问答策略。
 * 指定豆包搜索来源并由协议父类执行；不在应用侧执行搜索或拼接搜索资料。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiDoubaoStrategy extends AbstractResponsesApiStrategy {
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
        body.put("tools", List.of(Map.of("type", "web_search", "sources", List.of("doubao"))));
        body.put("tool_choice", "required");
        return body;
    }
}
