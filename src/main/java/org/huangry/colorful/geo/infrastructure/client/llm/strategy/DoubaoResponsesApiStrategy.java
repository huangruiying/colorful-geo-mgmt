package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractResponsesApiStrategy;
import org.springframework.stereotype.Component;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import java.util.Map;
import java.util.List;

/** 豆包 Responses（原生联网）适配，复用父类交互及解析，由 Spring 注册；不选择渠道。 */
@Component
public class DoubaoResponsesApiStrategy extends AbstractResponsesApiStrategy {

    /** 方舟基础联网插件，模型和插件权限由远端校验。 */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = super.buildRequestBody(request, channel);
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("tool_choice", "required");
        return body;
    }
}
