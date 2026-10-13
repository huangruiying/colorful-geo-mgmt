package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractResponsesApiStrategy;
import org.springframework.stereotype.Component;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import java.util.Map;
import java.util.List;

/**
 * 豆包 Responses 联网策略，注册为 doubaoResponsesApiStrategy。
 * <p>通过 buildRequestBody 声明联网工具，query 的 HTTP 调用与引用解析由模板负责；
 * 模型、账号是否支持该工具由远端校验，不保证每次返回引用。</p>
 */
@Component
public class DoubaoResponsesApiStrategy extends AbstractResponsesApiStrategy {

    /**
     * 在基础问题请求上声明方舟联网工具，并要求使用工具。
     * @param request 本次独立问题
     * @param channel 已选中的渠道配置
     * @return 带联网工具的 Responses 请求体
     */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        // 先保留模板的问题和模型，再补充方舟联网要求。
        Map<String, Object> body = super.buildRequestBody(request, channel);
        // 只提供联网工具；不支持时由接口拒绝，不静默改成普通问答。
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("tool_choice", "required");
        return body;
    }
}
