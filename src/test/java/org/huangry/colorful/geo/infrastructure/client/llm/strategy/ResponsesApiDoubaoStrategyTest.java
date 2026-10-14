package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证豆包搜索来源与流式请求契约，不调用外部模型。 */
class ResponsesApiDoubaoStrategyTest {
    /** 指定豆包来源并强制搜索，保留模型与单次问题配置。 */
    @Test
    void shouldRequireDoubaoSearchWithStreaming() {
        var channel = new GeoLlmChannelEntity();
        channel.setModelName("doubao-seed-2-1-pro-260915");
        channel.setMaxTokens(2000);
        var body = new ResponsesApiDoubaoStrategy().buildRequestBody(new LLMQueryRequest("今天有什么 AI 领域的热点新闻？"), channel);

        assertEquals(List.of(Map.of("type", "web_search", "sources", List.of("doubao"))), body.get("tools"));
        assertEquals("required", body.get("tool_choice"));
        assertEquals(channel.getModelName(), body.get("model"));
        assertEquals(2000, body.get("max_output_tokens"));
        assertEquals(true, body.get("stream"));
        assertFalse(body.containsKey("temperature"));
        assertEquals(false, body.get("store"));
        assertEquals(List.of(Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", "今天有什么 AI 领域的热点新闻？")))), body.get("input"));
    }
}
