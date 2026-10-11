package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Responses 协议自动提供搜索工具，无需调用方选择。 */
class OpenAiWebSearchStrategyTest {
    /** 没有结构化引用时，正文链接不能被当成引用。 */
    @Test
    void shouldKeepCitationsEmptyWithoutAnnotations() throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"参考 https://example.com\",\"annotations\":[]}]}]}");
        var response = new OpenAiWebSearchStrategy().parseResponse(body);
        assertEquals("参考 https://example.com", response.content());
        assertTrue(response.citations().isEmpty());
    }

    /** 默认单轮请求也声明 web_search，同时保留模型与消息。 */
    @Test
    void shouldAutomaticallySendSearchTool() {
        var strategy = new OpenAiWebSearchStrategy();
        var config = new LLMConnectionConfig();
        config.setModel("test-model");
        var ordinary = LLMQueryRequest.userMessage("问题");
        var plain = strategy.buildRequestBody(ordinary, config);
        assertEquals(List.of(Map.of("type", "web_search")), plain.get("tools"));
        assertEquals("test-model", plain.get("model"));
        assertEquals(false, plain.get("store"));
    }
}
