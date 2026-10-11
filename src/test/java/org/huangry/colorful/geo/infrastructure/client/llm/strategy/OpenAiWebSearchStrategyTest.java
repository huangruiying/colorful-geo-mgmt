package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Responses 协议的搜索能力与单次请求的搜索开关独立。 */
class OpenAiWebSearchStrategyTest {
    /** 普通问答不声明工具；搜索请求才声明 web_search。 */
    @Test
    void shouldSendSearchToolOnlyWhenRequested() {
        var strategy = new OpenAiWebSearchStrategy();
        var config = new LLMConnectionConfig();
        config.setModel("test-model");
        var ordinary = LLMQueryRequest.userMessage("问题");
        var plain = strategy.buildRequestBody(ordinary, config);
        assertFalse(plain.containsKey("tools"));
        assertEquals("test-model", plain.get("model"));
        assertEquals(false, plain.get("store"));
        var search = strategy.buildRequestBody(
            new LLMQueryRequest(ordinary.messages(), true, null, null), config);
        assertEquals(List.of(Map.of("type", "web_search")), search.get("tools"));
        assertEquals(plain.get("input"), search.get("input"));
    }
}
